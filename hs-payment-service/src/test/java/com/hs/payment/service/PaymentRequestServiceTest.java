package com.hs.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.PaymentRequestResponse;
import com.hs.payment.dto.RejectReceiptRequest;
import com.hs.payment.dto.ReportTransferRequest;
import com.hs.payment.model.BankAccount;
import com.hs.payment.model.PaymentEvidence;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.BankAccountStatus;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.*;
import com.hs.payment.service.qr.VietQrProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRequestServiceTest {

    @Mock
    private PaymentRequestRepository paymentRequestRepository;
    @Mock
    private PaymentLineItemRepository paymentLineItemRepository;
    @Mock
    private PaymentEvidenceRepository paymentEvidenceRepository;
    @Mock
    private PaymentEventRepository paymentEventRepository;
    @Mock
    private DepositRecordRepository depositRecordRepository;
    @Mock
    private BankAccountService bankAccountService;
    @Mock
    private VietQrProvider vietQrProvider;
    @Mock
    private TransferReferenceGenerator transferReferenceGenerator;
    @Mock
    private com.hs.storage.repository.StorageObjectRepository storageObjectRepository;
    @Mock
    private PaymentProofUploadSessionService paymentProofUploadSessionService;
    @Mock
    private BillingTime billingTime;
    @Mock private org.springframework.context.ApplicationEventPublisher receiptEvents;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PaymentRequestService paymentRequestService;

    private BankAccount landlordAccount;
    private BankAccount tenantAccount;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"INITIAL,1000000,0","INITIAL,1000000,2000000","RESERVATION_DEPOSIT,0,2000000"})
    void refundAllocationComesFromThePaidReceiptAndMatchesTheQrTotal(PaymentType type, String rentText, String depositText) throws Exception {
        when(paymentRequestRepository.findByOnboardingKey("CANCEL_REFUND:rental")).thenReturn(Optional.empty());
        BigDecimal rent=new BigDecimal(rentText), deposit=new BigDecimal(depositText), total=rent.add(deposit);
        var source=PaymentRequest.builder().id("paid").rentalRequestId("rental").listingId("listing").payerId("tenant").payeeId("owner")
                .type(type).status(PaymentStatus.CONFIRMED).totalAmount(total)
                .payerBankAccountSnapshot("{\"bankBin\":\"970436\",\"bankCode\":\"VCB\",\"bankName\":\"Bank\",\"accountNumber\":\"222222\",\"accountHolderName\":\"TENANT\"}")
                .payeeBankAccountSnapshot("{\"accountNumber\":\"111111\"}").build();
        var rows=List.of(com.hs.payment.model.PaymentLineItem.builder().type("RENT").amount(rent).displayName("Tiền thuê kỳ đầu").build(),
                com.hs.payment.model.PaymentLineItem.builder().type("DEPOSIT").amount(deposit).displayName("Tiền đặt cọc").build());
        when(paymentLineItemRepository.findByPaymentRequestIdOrderBySortOrderAsc("paid")).thenReturn(rows);
        when(billingTime.now()).thenReturn(Instant.parse("2026-11-06T08:00:00Z"));
        when(transferReferenceGenerator.generateUniqueReference()).thenReturn("HSREFUND");
        when(paymentRequestRepository.save(any())).thenAnswer(inv->{PaymentRequest payment=inv.getArgument(0);payment.setId("refund");return payment;});
        paymentRequestService.createRefundObligation(source);
        var captured=org.mockito.ArgumentCaptor.forClass(com.hs.payment.model.PaymentLineItem.class);
        verify(paymentLineItemRepository,times((rent.signum()>0?1:0)+(deposit.signum()>0?1:0))).save(captured.capture());
        var allocated=captured.getAllValues();
        assertEquals(0,total.compareTo(allocated.stream().map(com.hs.payment.model.PaymentLineItem::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add)));
        assertTrue(allocated.stream().allMatch(row->"refund".equals(row.getPaymentRequestId())&&row.getAmount().signum()>0));
        assertEquals(rent.signum()>0,allocated.stream().anyMatch(row->"RENT_REFUND".equals(row.getType())));
        assertEquals(deposit.signum()>0,allocated.stream().anyMatch(row->"DEPOSIT_REFUND".equals(row.getType())));
        verify(vietQrProvider).generateQrImageUrl("970436","222222","TENANT",total,"HSREFUND");
        var refundCapture=org.mockito.ArgumentCaptor.forClass(PaymentRequest.class);verify(paymentRequestRepository).save(refundCapture.capture());
        var refund=refundCapture.getValue();assertEquals("owner",refund.getPayerId());assertEquals("tenant",refund.getPayeeId());
        assertEquals(source.getPayerBankAccountSnapshot(),refund.getPayeeBankAccountSnapshot());
        assertEquals(PaymentStatus.CONFIRMED,source.getStatus());assertEquals(total,source.getTotalAmount());
        when(paymentRequestRepository.findByOnboardingKey("REFUND:paid")).thenReturn(Optional.of(refund));
        paymentRequestService.createRefundObligation(source);
        verify(paymentRequestRepository,times(1)).save(any());
        verify(paymentLineItemRepository,times(allocated.size())).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void missingOrInconsistentHistoricalAllocationUsesTheKnownRefundTotal(boolean inconsistent) {
        var source=PaymentRequest.builder().id("paid").type(PaymentType.INITIAL).payerId("tenant").payeeId("owner")
                .status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("1000000")).build();
        when(paymentLineItemRepository.findByPaymentRequestIdOrderBySortOrderAsc("paid")).thenReturn(inconsistent
                ?List.of(com.hs.payment.model.PaymentLineItem.builder().type("DEPOSIT").amount(new BigDecimal("500000")).build()):List.of());
        when(billingTime.now()).thenReturn(Instant.parse("2026-11-06T08:00:00Z"));
        when(paymentRequestRepository.save(any())).thenAnswer(inv->{PaymentRequest payment=inv.getArgument(0);payment.setId("refund");return payment;});
        paymentRequestService.createRefundObligation(source);
        verify(paymentLineItemRepository).save(argThat(row->"REFUND".equals(row.getType())&&row.getAmount().compareTo(source.getTotalAmount())==0));
    }

    @Test void aZeroRefundCreatesNoPaymentQrOrAllocation() {
        var source=PaymentRequest.builder().id("paid").status(PaymentStatus.CONFIRMED).totalAmount(BigDecimal.ZERO).build();
        paymentRequestService.createRefundObligation(source);
        verifyNoInteractions(paymentLineItemRepository,vietQrProvider,depositRecordRepository);
        verify(paymentRequestRepository,never()).save(any());
    }

    private PaymentRequest cancellationSource(String id, PaymentType type, String amount) {
        var payment=PaymentRequest.builder().id(id).rentalRequestId("rental").listingId("listing").contractId("lease")
                .payerId("tenant").payeeId("owner").type(type).direction(com.hs.payment.model.constant.PaymentDirection.TENANT_TO_LANDLORD)
                .status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal(amount))
                .payerBankAccountSnapshot("{\"bankBin\":\"970436\",\"accountNumber\":\"222222\",\"accountHolderName\":\"TENANT\"}").build();
        when(paymentRequestRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
        return payment;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"split","combined","deposit-only","rent-only"})
    void cancellationCreatesOneTransferForAllActualReceipts(String scenario) {
        var deposit=new BigDecimal("1000000");var rent=new BigDecimal("2000000");
        var sources=new java.util.ArrayList<PaymentRequest>();
        if(!"combined".equals(scenario)&&!"rent-only".equals(scenario))sources.add(cancellationSource("deposit",PaymentType.RESERVATION_DEPOSIT,deposit.toPlainString()));
        if(!"deposit-only".equals(scenario))sources.add(cancellationSource("rent",PaymentType.INITIAL,"combined".equals(scenario)?"3000000":rent.toPlainString()));
        for(var source:sources)when(paymentLineItemRepository.findByPaymentRequestIdOrderBySortOrderAsc(source.getId())).thenReturn(
                "combined".equals(scenario)?List.of(
                    com.hs.payment.model.PaymentLineItem.builder().type("RENT").amount(rent).build(),
                    com.hs.payment.model.PaymentLineItem.builder().type("DEPOSIT").amount(deposit).build()):List.of(
                    com.hs.payment.model.PaymentLineItem.builder().type(source.getType()==PaymentType.INITIAL?"RENT":"DEPOSIT").amount(source.getTotalAmount()).build()));
        when(billingTime.now()).thenReturn(Instant.parse("2026-11-06T08:00:00Z"));
        when(transferReferenceGenerator.generateUniqueReference()).thenReturn("HSREFUND");
        when(paymentRequestRepository.save(any())).thenAnswer(inv->{PaymentRequest payment=inv.getArgument(0);payment.setId("refund");return payment;});
        paymentRequestService.createCancellationRefund(sources);
        var paymentCapture=org.mockito.ArgumentCaptor.forClass(PaymentRequest.class);verify(paymentRequestRepository).save(paymentCapture.capture());
        var refund=paymentCapture.getValue();var total=sources.stream().map(PaymentRequest::getTotalAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        assertEquals(0,total.compareTo(refund.getTotalAmount()));assertEquals("owner",refund.getPayerId());assertEquals("tenant",refund.getPayeeId());
        assertEquals("CANCEL_REFUND:rental",refund.getOnboardingKey());assertTrue(sources.stream().allMatch(s->InitialRefundSources.covers(refund,s,objectMapper)));
        var lineCapture=org.mockito.ArgumentCaptor.forClass(com.hs.payment.model.PaymentLineItem.class);
        int count="split".equals(scenario)||"combined".equals(scenario)?2:1;
        verify(paymentLineItemRepository,times(count)).save(lineCapture.capture());var rows=lineCapture.getAllValues();
        assertEquals(0,total.compareTo(rows.stream().map(com.hs.payment.model.PaymentLineItem::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add)));
        assertEquals(count,rows.stream().map(com.hs.payment.model.PaymentLineItem::getSortOrder).distinct().count());
        verify(vietQrProvider).generateQrImageUrl("970436","222222","TENANT",total,"HSREFUND");
        assertTrue(sources.stream().allMatch(s->s.getStatus()==PaymentStatus.CONFIRMED));
        when(paymentRequestRepository.findByOnboardingKey("CANCEL_REFUND:rental")).thenReturn(Optional.of(refund));
        paymentRequestService.createCancellationRefund(sources);verify(paymentRequestRepository,times(1)).save(any());
        assertThrows(IllegalStateException.class,()->paymentRequestService.createRefundObligation(sources.getFirst()));
        sources.getFirst().setTotalAmount(total.add(BigDecimal.ONE));
        assertThrows(IllegalStateException.class,()->paymentRequestService.createCancellationRefund(sources));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"reported","disputed","rejected","wrong-rental","wrong-payer","wrong-payee","wrong-listing","wrong-direction","wrong-type","negative","zero","wrong-currency","duplicate","existing-refund"})
    void cancellationNeverGroupsUnreceivedOrUnrelatedMoney(String scenario) {
        var deposit=cancellationSource("deposit",PaymentType.RESERVATION_DEPOSIT,"1000000");
        var rent=cancellationSource("rent",PaymentType.INITIAL,"2000000");
        var sources=new java.util.ArrayList<>(List.of(deposit,rent));
        switch(scenario) {
            case "reported" -> rent.setStatus(PaymentStatus.TRANSFER_REPORTED);
            case "disputed" -> rent.setStatus(PaymentStatus.DISPUTED);
            case "rejected" -> rent.setStatus(PaymentStatus.REJECTED);
            case "wrong-rental" -> rent.setRentalRequestId("other");
            case "wrong-payer" -> rent.setPayerId("other");
            case "wrong-payee" -> rent.setPayeeId("other");
            case "wrong-listing" -> rent.setListingId("other");
            case "wrong-direction" -> rent.setDirection(com.hs.payment.model.constant.PaymentDirection.LANDLORD_TO_TENANT);
            case "wrong-type" -> rent.setType(PaymentType.MONTHLY_RENT);
            case "negative" -> rent.setTotalAmount(BigDecimal.ONE.negate());
            case "zero" -> rent.setTotalAmount(BigDecimal.ZERO);
            case "wrong-currency" -> rent.setCurrency("USD");
            case "duplicate" -> sources.add(deposit);
            case "existing-refund" -> when(paymentRequestRepository.findByRentalRequestIdAndTypeIn(eq("rental"),any())).thenReturn(List.of(PaymentRequest.builder().id("old").build()));
        }
        assertThrows(IllegalStateException.class,()->paymentRequestService.createCancellationRefund(sources));
        verify(paymentRequestRepository,never()).save(any());verifyNoInteractions(paymentLineItemRepository,vietQrProvider);
    }

    @Test void rentOnlyRefundDoesNotRefundOrResetRetainedDeposit() {
        when(paymentRequestRepository.findByOnboardingKey("CANCEL_REFUND:rental")).thenReturn(Optional.empty());
        var source=PaymentRequest.builder().id("source").contractId("root").rentalRequestId("rental").listingId("listing")
            .payerId("tenant").payeeId("owner").status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("15000000")).build();
        when(billingTime.now()).thenReturn(Instant.parse("2026-11-02T03:00:00Z"));
        when(transferReferenceGenerator.generateUniqueReference()).thenReturn("HSREFUND1");
        when(paymentRequestRepository.save(any())).thenAnswer(i->{PaymentRequest p=i.getArgument(0);p.setId("refund");return p;});
        paymentRequestService.createRentRefundObligation(source,new BigDecimal("5000000"));
        var captor=org.mockito.ArgumentCaptor.forClass(PaymentRequest.class);verify(paymentRequestRepository).save(captor.capture());
        var refund=captor.getValue();assertEquals(new BigDecimal("5000000"),refund.getTotalAmount());assertEquals("root",refund.getContractId());
        verify(paymentLineItemRepository).save(argThat(row->"RENT_REFUND".equals(row.getType())&&row.getAmount().compareTo(refund.getTotalAmount())==0));
        assertEquals("owner",refund.getPayerId());assertEquals("tenant",refund.getPayeeId());assertEquals(PaymentType.INITIAL_PAYMENT_REFUND,refund.getType());
        assertEquals(PaymentStatus.AWAITING_TRANSFER,refund.getStatus());assertEquals(Instant.parse("2026-11-05T03:00:00Z"),refund.getDueAt());
        verifyNoInteractions(depositRecordRepository);assertEquals(PaymentStatus.CONFIRMED,source.getStatus());
        assertEquals(new BigDecimal("15000000"),source.getTotalAmount());
        when(paymentRequestRepository.findByOnboardingKey("REFUND:source")).thenReturn(Optional.of(refund));
        paymentRequestService.createRentRefundObligation(source,new BigDecimal("5000000"));verify(paymentRequestRepository,times(1)).save(any());
        assertThrows(IllegalArgumentException.class,()->paymentRequestService.createRentRefundObligation(source,new BigDecimal("16000000")));
    }

    @Test void depositDeltaCreatesOnlyDifferenceAndReusesSameObligation() {
        when(bankAccountService.getDefaultIncomingAccount("landlord-1")).thenReturn(landlordAccount);
        when(transferReferenceGenerator.generateUniqueReference()).thenReturn("HSDELTA1");
        when(paymentRequestRepository.save(any())).thenAnswer(i->{PaymentRequest p=i.getArgument(0);p.setId("delta");return p;});
        var due=Instant.parse("2026-11-19T00:00:00Z");
        var payment=paymentRequestService.createDepositAdjustment("appendix","root","rental","listing","landlord-1","tenant-1",new BigDecimal("5000000"),due);
        assertEquals(PaymentType.DEPOSIT_ADJUSTMENT,payment.getType());assertEquals(new BigDecimal("5000000"),payment.getTotalAmount());
        assertEquals("tenant-1",payment.getPayerId());assertEquals("landlord-1",payment.getPayeeId());
        verifyNoInteractions(depositRecordRepository);
        when(paymentRequestRepository.findByInvoiceId("appendix")).thenReturn(Optional.of(payment));
        assertSame(payment,paymentRequestService.createDepositAdjustment("appendix","root","rental","listing","landlord-1","tenant-1",new BigDecimal("5000000"),due));
        assertThrows(IllegalStateException.class,()->paymentRequestService.createDepositAdjustment("appendix","root","rental","listing","landlord-1","tenant-1",new BigDecimal("-5000000"),due));
        verify(paymentRequestRepository,times(1)).save(any());
    }
    @Test void depositHeldBalanceChangesOnlyOnExplicitReceiptAndCannotBeConfirmedTwice() {
        for(var direction:com.hs.payment.model.constant.PaymentDirection.values()) {
            var payment=PaymentRequest.builder().id("delta").rentalRequestId("rental").type(PaymentType.DEPOSIT_ADJUSTMENT)
                    .direction(direction).payeeId("receiver").payerId("sender").status(PaymentStatus.TRANSFER_REPORTED).totalAmount(new BigDecimal("5000000")).build();
            var deposit=com.hs.payment.model.DepositRecord.builder().originalAmount(new BigDecimal("20000000")).heldAmount(new BigDecimal("20000000"))
                    .refundableAmount(new BigDecimal("20000000")).status(com.hs.payment.model.constant.DepositStatus.HELD).build();
            when(paymentRequestRepository.findById("delta")).thenReturn(Optional.of(payment));
            when(paymentRequestRepository.save(any())).thenAnswer(i->i.getArgument(0));
            when(depositRecordRepository.findByRentalRequestIdForUpdate("rental")).thenReturn(Optional.of(deposit));
            when(billingTime.now()).thenReturn(Instant.parse("2026-11-15T03:00:00Z"));
            paymentRequestService.confirmReceipt("delta","receiver");
            BigDecimal expected=new BigDecimal(direction==com.hs.payment.model.constant.PaymentDirection.TENANT_TO_LANDLORD?"25000000":"15000000");
            assertEquals(expected,deposit.getHeldAmount());assertEquals(new BigDecimal("20000000"),deposit.getOriginalAmount());
            paymentRequestService.confirmReceipt("delta","receiver");assertEquals(expected,deposit.getHeldAmount());
        }
        verify(depositRecordRepository,times(2)).save(any());
    }

    @Test
    void settlementRefundUsesTenantBankAndNeverCollectsInitialPaymentAgain() {
        when(bankAccountService.getDefaultRefundAccount("tenant-1")).thenReturn(tenantAccount);
        when(transferReferenceGenerator.generateUniqueReference()).thenReturn("HSREFUND1");
        when(vietQrProvider.generateQrImageUrl(anyString(),anyString(),anyString(),any(),anyString())).thenReturn("https://vietqr.test/refund");
        when(paymentRequestRepository.save(any())).thenAnswer(i->{PaymentRequest p=i.getArgument(0);p.setId("refund");return p;});
        Instant due=Instant.parse("2027-01-29T03:00:00Z");
        PaymentRequest p=paymentRequestService.createContractSettlement("contract","rental","listing","landlord-1","tenant-1",
                new BigDecimal("20000000"),due,true,"Hoàn cọc");
        assertEquals(PaymentType.DEPOSIT_REFUND,p.getType());
        assertEquals(com.hs.payment.model.constant.PaymentDirection.LANDLORD_TO_TENANT,p.getDirection());
        assertEquals(due,p.getDueAt());
        assertTrue(p.getPayeeBankAccountSnapshot().contains("1234567890"));
        verify(bankAccountService,never()).getDefaultIncomingAccount(anyString());
        verify(paymentLineItemRepository).save(argThat(i->i.getType().equals("DEPOSIT_REFUND")));
        when(paymentRequestRepository.findByContractIdAndType("contract",PaymentType.DEPOSIT_REFUND)).thenReturn(Optional.of(p));
        assertSame(p,paymentRequestService.createContractSettlement("contract","rental","listing","landlord-1","tenant-1",
                new BigDecimal("20000000"),due,true,"Hoàn cọc"));
        assertThrows(IllegalStateException.class,()->paymentRequestService.createContractSettlement("contract","rental","listing","landlord-1","tenant-1",
                new BigDecimal("1"),due,true,"Sửa số tiền"));
        verify(paymentRequestRepository,times(1)).save(any());
    }

    @Test
    void refundProofDoesNotReleaseDepositUntilTenantExplicitlyConfirmsReceipt() {
        PaymentRequest p=PaymentRequest.builder().id("refund").contractId("contract").rentalRequestId("rental")
                .type(PaymentType.DEPOSIT_REFUND).direction(com.hs.payment.model.constant.PaymentDirection.LANDLORD_TO_TENANT)
                .payerId("landlord-1").payeeId("tenant-1").status(PaymentStatus.OVERDUE).totalAmount(new BigDecimal("20000000")).build();
        var deposit=com.hs.payment.model.DepositRecord.builder().refundPaymentRequestId("refund")
                .status(com.hs.payment.model.constant.DepositStatus.REFUND_DUE).heldAmount(p.getTotalAmount()).refundableAmount(p.getTotalAmount()).build();
        when(paymentRequestRepository.findById("refund")).thenReturn(Optional.of(p));
        when(paymentRequestRepository.save(any())).thenAnswer(i->i.getArgument(0));
        when(depositRecordRepository.findByRefundPaymentRequestId("refund")).thenReturn(Optional.of(deposit));
        when(billingTime.now()).thenReturn(Instant.parse("2027-01-30T03:00:00Z"));
        when(paymentProofUploadSessionService.consumeSession("session","refund","landlord-1")).thenReturn("proof");
        assertThrows(AppException.class,()->paymentRequestService.reportTransfer("refund","tenant-1",ReportTransferRequest.builder().build()));
        paymentRequestService.reportTransfer("refund","landlord-1",ReportTransferRequest.builder().evidenceUploadSessionId("session").expectedAmount(p.getTotalAmount()).build());
        assertEquals(com.hs.payment.model.constant.DepositStatus.REFUND_REPORTED,deposit.getStatus());
        assertEquals(new BigDecimal("20000000"),deposit.getHeldAmount());
        assertThrows(AppException.class,()->paymentRequestService.confirmReceipt("refund","landlord-1"));
        paymentRequestService.confirmReceipt("refund","tenant-1");
        assertEquals(PaymentStatus.CONFIRMED,p.getStatus());
        assertEquals(com.hs.payment.model.constant.DepositStatus.REFUNDED,deposit.getStatus());
        assertEquals(BigDecimal.ZERO,deposit.getHeldAmount());
        assertNull(p.getContractDueAt());
        assertEquals(Instant.parse("2027-01-30T03:00:00Z"),p.getConfirmedAt());
    }

    @Test
    void expiredRefundRemainsPayableAndReportedProofIsNotAutoConfirmed() {
        PaymentRequest p=PaymentRequest.builder().id("refund").contractId("contract").type(PaymentType.DEPOSIT_REFUND)
                .status(PaymentStatus.AWAITING_TRANSFER).dueAt(Instant.parse("2027-01-29T03:00:00Z")).qrImageUrl("refund-qr").build();
        when(paymentRequestRepository.findByIdForUpdate("refund")).thenReturn(Optional.of(p));
        paymentRequestService.syncContractSettlementDeadline("refund",Instant.parse("2027-01-30T03:00:00Z"));
        assertEquals(PaymentStatus.OVERDUE,p.getStatus());
        assertEquals("refund-qr",p.getQrImageUrl());
        p.setStatus(PaymentStatus.TRANSFER_REPORTED);
        paymentRequestService.syncContractSettlementDeadline("refund",Instant.parse("2027-02-01T03:00:00Z"));
        assertEquals(PaymentStatus.TRANSFER_REPORTED,p.getStatus());
        verify(paymentRequestRepository,times(1)).save(p);
    }

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(paymentRequestService,"entityManager",mock(jakarta.persistence.EntityManager.class));
        landlordAccount = BankAccount.builder()
                .id("acc-landlord")
                .userId("landlord-1")
                .bankBin("970422")
                .bankCode("MB")
                .bankName("MB Bank")
                .accountNumber("0987654321")
                .accountHolderName("TRAN VAN CHU NHA")
                .defaultForIncomingPayments(true)
                .status(BankAccountStatus.ACTIVE)
                .build();

        tenantAccount = BankAccount.builder()
                .id("acc-tenant")
                .userId("tenant-1")
                .bankBin("970436")
                .bankCode("VCB")
                .bankName("Vietcombank")
                .accountNumber("1234567890")
                .accountHolderName("NGUYEN VAN NGUOI THUE")
                .defaultForRefunds(true)
                .status(BankAccountStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("createInitialPayment creates payment with VietQR and reference")
    void createInitialPayment_Success() {
        when(paymentRequestRepository.findByRentalRequestIdAndType("req-1", PaymentType.INITIAL))
                .thenReturn(Optional.empty());
        when(bankAccountService.getDefaultIncomingAccount("landlord-1")).thenReturn(landlordAccount);
        when(bankAccountService.getDefaultRefundAccount("tenant-1")).thenReturn(tenantAccount);
        when(transferReferenceGenerator.generateUniqueReference()).thenReturn("HS12345678");
        when(vietQrProvider.generateQrImageUrl(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn("https://img.vietqr.io/image/970422-0987654321-compact2.png?amount=15000000&addInfo=HS12345678");

        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> {
            PaymentRequest p = i.getArgument(0);
            p.setId("pay-1");
            return p;
        });

        PaymentRequest result = paymentRequestService.createInitialPayment(
                "req-1",
                "listing-1",
                "tenant-1",
                "landlord-1",
                new BigDecimal("10000000"),
                new BigDecimal("5000000"),
                "[]",
                "[]",
                Instant.now().plusSeconds(86400)
        );

        assertNotNull(result);
        assertEquals("HS12345678", result.getTransferReference());
        assertEquals(PaymentStatus.AWAITING_TRANSFER, result.getStatus());
        assertEquals(new BigDecimal("15000000"), result.getTotalAmount());
        verify(paymentLineItemRepository, never()).save(argThat(item -> "SERVICE_FEE".equals(item.getType())));
        verify(paymentLineItemRepository, atLeastOnce()).save(any());
        verify(paymentEventRepository, atLeastOnce()).save(any());
    }

    @Test
    void rolloverCancelsOldMonthlyQrButRefusesReportedTransfer() {
        PaymentRequest payment = PaymentRequest.builder().id("monthly-1")
                .invoiceId("invoice-1").type(PaymentType.MONTHLY_RENT)
                .status(PaymentStatus.OVERDUE).qrImageUrl("old-qr").build();
        when(paymentRequestRepository.findByInvoiceId("invoice-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.findByIdForUpdate("monthly-1")).thenReturn(Optional.of(payment));
        when(billingTime.now()).thenReturn(Instant.parse("2026-11-10T03:00:00Z"));

        paymentRequestService.cancelMonthlyForRollover("invoice-1");
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        assertNull(payment.getQrImageUrl());
        verify(paymentRequestRepository).save(payment);

        payment.setStatus(PaymentStatus.TRANSFER_REPORTED);
        assertThrows(IllegalStateException.class,
                () -> paymentRequestService.cancelMonthlyForRollover("invoice-1"));
    }

    @Test
    void monthlyPaymentIsIdempotentAndHasItemizedVietQr() {
        when(paymentRequestRepository.findByInvoiceId("invoice-1")).thenReturn(Optional.empty());
        when(bankAccountService.getDefaultIncomingAccount("landlord-1")).thenReturn(landlordAccount);
        when(transferReferenceGenerator.generateUniqueReference()).thenReturn("HS87654321");
        when(vietQrProvider.generateQrImageUrl(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn("https://vietqr.test/monthly");
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> {
            PaymentRequest p = i.getArgument(0); p.setId("monthly-1"); return p;
        });
        PaymentRequest created = paymentRequestService.createMonthlyPayment("invoice-1", "contract-1",
                "req-1", "listing-1", "tenant-1", "landlord-1", new BigDecimal("210000"),
                "[{\"type\":\"ELECTRICITY\",\"description\":\"Tiền điện\",\"quantity\":\"60\",\"unitPrice\":\"3500\",\"amount\":\"210000\"}]",
                Instant.parse("2026-11-05T16:59:59Z"));
        assertEquals("invoice-1", created.getInvoiceId());
        assertEquals(PaymentType.MONTHLY_RENT, created.getType());
        assertEquals("https://vietqr.test/monthly", created.getQrImageUrl());
        verify(paymentLineItemRepository).save(argThat(item -> "ELECTRICITY".equals(item.getType())
                && item.getAmount().compareTo(new BigDecimal("210000")) == 0));

        when(paymentRequestRepository.findByInvoiceId("invoice-1")).thenReturn(Optional.of(created));
        assertSame(created, paymentRequestService.createMonthlyPayment("invoice-1", "contract-1",
                "req-1", "listing-1", "tenant-1", "landlord-1", new BigDecimal("210000"), "[]",
                Instant.parse("2026-11-05T16:59:59Z")));
        verify(paymentRequestRepository, times(1)).save(any(PaymentRequest.class));
    }

    @Test
    void monthlyOverdueCanStillReportTransferAndConfirmationDoesNotOpenContractWindow() {
        PaymentRequest monthly = PaymentRequest.builder().id("monthly-1").type(PaymentType.MONTHLY_RENT)
                .rentalRequestId("req-1").payerId("tenant-1").payeeId("landlord-1")
                .status(PaymentStatus.OVERDUE).totalAmount(new BigDecimal("210000")).build();
        when(paymentRequestRepository.findById("monthly-1")).thenReturn(Optional.of(monthly));
        when(paymentRequestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(paymentProofUploadSessionService.consumeSession("session-1", "monthly-1", "tenant-1"))
                .thenReturn("proof-1");
        when(billingTime.now()).thenReturn(Instant.parse("2026-11-07T00:00:00Z"));
        paymentRequestService.reportTransfer("monthly-1", "tenant-1",
                ReportTransferRequest.builder().evidenceUploadSessionId("session-1")
                        .expectedAmount(new BigDecimal("210000")).build());
        assertEquals(PaymentStatus.TRANSFER_REPORTED, monthly.getStatus());
        paymentRequestService.confirmReceipt("monthly-1", "landlord-1");
        assertEquals(PaymentStatus.CONFIRMED, monthly.getStatus());
        assertEquals(Instant.parse("2026-11-07T00:00:00Z"), monthly.getConfirmedAt());
        assertNull(monthly.getContractDueAt());
    }

    @Test
    void monthlyTransferRejectsStaleAmountBeforeConsumingProof() {
        PaymentRequest monthly = PaymentRequest.builder().id("monthly-1").type(PaymentType.MONTHLY_RENT)
                .payerId("tenant-1").payeeId("landlord-1")
                .status(PaymentStatus.OVERDUE).totalAmount(new BigDecimal("220000")).build();
        when(paymentRequestRepository.findById("monthly-1")).thenReturn(Optional.of(monthly));
        assertThrows(AppException.class, () -> paymentRequestService.reportTransfer("monthly-1", "tenant-1",
                ReportTransferRequest.builder().evidenceUploadSessionId("session-1")
                        .expectedAmount(new BigDecimal("210000")).build()));
        verify(paymentProofUploadSessionService, never()).consumeSession(anyString(), anyString(), anyString());
        assertEquals(PaymentStatus.OVERDUE, monthly.getStatus());
    }

    @Test
    @DisplayName("reportTransfer updates status to TRANSFER_REPORTED and saves evidence")
    void reportTransfer_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));

        com.hs.storage.model.StorageObject mockStorage = com.hs.storage.model.StorageObject.builder()
                .id("storage-1")
                .ownerId("tenant-1")
                .status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.PAYMENT_PROOF)
                .visibility(com.hs.storage.model.constant.StorageVisibility.PRIVATE)
                .referenceType("PAYMENT_REQUEST")
                .referenceId("pay-1")
                .build();
        when(storageObjectRepository.findById("storage-1")).thenReturn(Optional.of(mockStorage));

        ReportTransferRequest request = ReportTransferRequest.builder()
                .proofStorageId("storage-1")
                .build();

        PaymentRequestResponse resp = paymentRequestService.reportTransfer("pay-1", "tenant-1", request);

        assertEquals(PaymentStatus.TRANSFER_REPORTED, payment.getStatus());
        verify(paymentEvidenceRepository).save(any(PaymentEvidence.class));
        verify(paymentEventRepository).save(any());
    }

    @Test
    @DisplayName("reportTransfer with valid evidenceUploadSessionId consumes session and transitions to TRANSFER_REPORTED")
    void reportTransfer_WithEvidenceUploadSessionId_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentProofUploadSessionService.consumeSession("sess-123", "pay-1", "tenant-1"))
                .thenReturn("storage-from-session");

        ReportTransferRequest request = ReportTransferRequest.builder()
                .evidenceUploadSessionId("sess-123")
                .build();

        PaymentRequestResponse resp = paymentRequestService.reportTransfer("pay-1", "tenant-1", request);

        assertEquals(PaymentStatus.TRANSFER_REPORTED, payment.getStatus());
        verify(paymentProofUploadSessionService).consumeSession("sess-123", "pay-1", "tenant-1");
        verify(paymentEvidenceRepository).save(any(PaymentEvidence.class));
        verify(paymentEventRepository).save(any());
    }

    @Test
    @DisplayName("reportTransfer without proofStorageId throws PAYMENT_PROOF_REQUIRED")
    void reportTransfer_MissingProof_ThrowsException() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .build();

        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));

        ReportTransferRequest request = ReportTransferRequest.builder().build();

        AppException ex = assertThrows(AppException.class, () ->
                paymentRequestService.reportTransfer("pay-1", "tenant-1", request));
        assertEquals(PaymentErrorCode.PAYMENT_PROOF_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("reportTransfer with proof owned by different user throws PAYMENT_PROOF_INVALID")
    void reportTransfer_WrongOwnerProof_ThrowsException() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .build();

        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));

        com.hs.storage.model.StorageObject wrongOwnerStorage = com.hs.storage.model.StorageObject.builder()
                .id("storage-fake")
                .ownerId("hacker-user")
                .status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.PAYMENT_PROOF)
                .visibility(com.hs.storage.model.constant.StorageVisibility.PRIVATE)
                .referenceType("PAYMENT_REQUEST")
                .referenceId("pay-1")
                .build();
        when(storageObjectRepository.findById("storage-fake")).thenReturn(Optional.of(wrongOwnerStorage));

        ReportTransferRequest request = ReportTransferRequest.builder()
                .proofStorageId("storage-fake")
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                paymentRequestService.reportTransfer("pay-1", "tenant-1", request));
        assertEquals(PaymentErrorCode.PAYMENT_PROOF_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("reportTransfer re-submission from REJECTED clears rejection info")
    void reportTransfer_ResubmitFromRejected_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.REJECTED)
                .rejectedAt(Instant.now())
                .rejectedReason("Chua thay bien dong so du")
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));

        com.hs.storage.model.StorageObject mockStorage = com.hs.storage.model.StorageObject.builder()
                .id("storage-2")
                .ownerId("tenant-1")
                .status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.PAYMENT_PROOF)
                .visibility(com.hs.storage.model.constant.StorageVisibility.PRIVATE)
                .referenceType("PAYMENT_REQUEST")
                .referenceId("pay-1")
                .build();
        when(storageObjectRepository.findById("storage-2")).thenReturn(Optional.of(mockStorage));

        ReportTransferRequest request = ReportTransferRequest.builder()
                .proofStorageId("storage-2")
                .build();

        paymentRequestService.reportTransfer("pay-1", "tenant-1", request);

        assertEquals(PaymentStatus.TRANSFER_REPORTED, payment.getStatus());
        assertNull(payment.getRejectedReason());
        assertNull(payment.getRejectedAt());
    }

    @Test
    @DisplayName("reportTransfer by unauthorized user throws exception")
    void reportTransfer_UnauthorizedUser_ThrowsException() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .build();

        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));

        ReportTransferRequest request = ReportTransferRequest.builder()
                .proofStorageId("storage-1")
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                paymentRequestService.reportTransfer("pay-1", "other-user", request));
        assertEquals(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("confirmReceipt by landlord changes status to CONFIRMED and creates deposit record")
    void confirmReceipt_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .rentalRequestId("req-1")
                .listingId("listing-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.TRANSFER_REPORTED)
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));

        PaymentRequestResponse resp = paymentRequestService.confirmReceipt("pay-1", "landlord-1");

        assertEquals(PaymentStatus.CONFIRMED, payment.getStatus());
        assertNotNull(payment.getConfirmedAt());
        assertNotNull(payment.getPayeeConfirmedAt());
        verify(paymentEventRepository).save(any());
    }

    @Test void onboardingReceiptPublishesOnlyAfterAuthorizedConfirmationAndCanBeRetried() {
        org.springframework.test.util.ReflectionTestUtils.setField(paymentRequestService,"receiptEvents",receiptEvents);
        var payment=PaymentRequest.builder().id("receipt").rentalRequestId("rental").payerId("tenant").payeeId("owner")
            .type(PaymentType.INITIAL).status(PaymentStatus.TRANSFER_REPORTED).totalAmount(new BigDecimal("100"))
            .direction(com.hs.payment.model.constant.PaymentDirection.TENANT_TO_LANDLORD).build();
        when(paymentRequestRepository.findById("receipt")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any())).thenAnswer(i->i.getArgument(0));
        when(billingTime.now()).thenReturn(Instant.parse("2026-11-01T03:00:00Z"));
        assertThrows(AppException.class,()->paymentRequestService.confirmReceipt("receipt","other"));
        verifyNoInteractions(receiptEvents);
        paymentRequestService.confirmReceipt("receipt","owner");
        paymentRequestService.confirmReceipt("receipt","owner");
        verify(receiptEvents,times(2)).publishEvent(new com.hs.payment.event.OnboardingReceiptConfirmed("rental"));
        verify(paymentRequestRepository,times(1)).save(any());
    }

    @Test
    @DisplayName("rejectReceipt changes status to REJECTED")
    void rejectReceipt_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.TRANSFER_REPORTED)
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));

        RejectReceiptRequest req = new RejectReceiptRequest("Chua thay tien vao tai khoan, vui long kiem tra lai");

        paymentRequestService.rejectReceipt("pay-1", "landlord-1", req);

        assertEquals(PaymentStatus.REJECTED, payment.getStatus());
        verify(paymentEventRepository).save(any());
    }
}
