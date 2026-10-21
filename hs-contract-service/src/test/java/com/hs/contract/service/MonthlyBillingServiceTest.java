package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.IssueMonthlyInvoiceRequest;
import com.hs.contract.dto.billing.InvoiceLine;
import com.hs.contract.dto.billing.MonthlyInvoiceResponse;
import com.hs.contract.dto.billing.CreateOverdueActionRequest;
import com.hs.contract.dto.billing.AcknowledgeOverdueActionRequest;
import com.hs.contract.dto.billing.OverdueAction;
import com.hs.contract.dto.billing.PaymentExtensionPayload;
import com.hs.contract.dto.billing.PaymentExtension;
import com.hs.contract.dto.billing.AcceptTerminationRequest;
import com.hs.contract.dto.billing.CompleteTerminationRequest;
import com.hs.contract.dto.billing.ForceTerminationRequest;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.model.MonthlyInvoice;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.ContractRepository;
import com.hs.contract.repository.ContractRevisionRepository;
import com.hs.contract.repository.MonthlyInvoiceRepository;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.service.ListingStatusService;
import com.hs.listing.service.ParkingReservationService;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.repository.DepositRecordRepository;
import com.hs.payment.dto.PaymentLineItemResponse;
import com.hs.payment.dto.PaymentRequestResponse;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.DepositRecord;
import com.hs.payment.model.constant.DepositStatus;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.service.PaymentRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonthlyBillingServiceTest {
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final ContractRevisionRepository revisions = mock(ContractRevisionRepository.class);
    private final RentalRequestRepository rentals = mock(RentalRequestRepository.class);
    private final MonthlyInvoiceRepository invoices = mock(MonthlyInvoiceRepository.class);
    private final PaymentRequestService payments = mock(PaymentRequestService.class);
    private final BillingTime time = mock(BillingTime.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final ListingStatusService listingStatusService = mock(ListingStatusService.class);
    private final DepositRecordRepository deposits = mock(DepositRecordRepository.class);
    private final ParkingReservationService parkingReservations = mock(ParkingReservationService.class);
    private MonthlyBillingService service;
    private MonthlyInvoice invoice;

    @BeforeEach
    void setUp() {
        service = new MonthlyBillingService(contracts, revisions, rentals, invoices,
                payments, new ObjectMapper().findAndRegisterModules(), time, tx,
                listingStatusService, deposits, parkingReservations);
        when(time.now()).thenReturn(Instant.parse("2026-11-01T03:00:00Z"));
        when(time.today()).thenReturn(LocalDate.of(2026, 11, 1));
        Contract contract = Contract.builder().id("contract").landlordId("landlord")
                .tenantId("tenant").rentalRequestId("rental").listingId("listing")
                .currentRevisionId("revision").status(ContractStatus.ACTIVE).build();
        invoice = MonthlyInvoice.builder().id("invoice").contractId("contract")
                .periodIndex(0).periodStart(LocalDate.of(2026, 10, 1))
                .periodEndExclusive(LocalDate.of(2026, 11, 1))
                .status(MonthlyInvoiceStatus.DRAFT).totalAmount(BigDecimal.ZERO).build();
        ContractRevision revision = ContractRevision.builder().id("revision")
                .financialSnapshot("{\"amountValue\":\"5000000\",\"amountNumber\":\"5.000.000 VNĐ/tháng\"}")
                .chargesSnapshot("[{\"name\":\"Điện\",\"billingMethod\":\"PER_KWH\",\"chargeType\":\"ELECTRICITY\",\"unitAmount\":\"3500\",\"amountAndMethod\":\"Tiền điện 3.500đ/kWh\"},"
                        + "{\"name\":\"Nước\",\"billingMethod\":\"PER_M3\",\"chargeType\":\"WATER\",\"unitAmount\":\"16000\",\"amountAndMethod\":\"Tiền nước 16.000đ/m³\"}]")
                .initialMetersSnapshot("{\"electricityInitial\":\"100\",\"waterInitial\":\"10\"}")
                .build();
        when(invoices.findByIdForUpdate("invoice")).thenReturn(Optional.of(invoice));
        when(invoices.findContractIdByInvoiceId(anyString())).thenReturn(Optional.of("contract"));
        when(contracts.findById("contract")).thenReturn(Optional.of(contract));
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        when(revisions.findById("revision")).thenReturn(Optional.of(revision));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026, 10, 1)).leaseMonths(12).build()));
        when(invoices.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PaymentRequest initial = PaymentRequest.builder().id("initial").status(PaymentStatus.CONFIRMED).build();
        when(payments.findByRentalRequestIdAndType("rental", PaymentType.INITIAL))
                .thenReturn(Optional.of(initial));
        when(payments.getPaymentRequest(eq("initial"),anyString())).thenReturn(PaymentRequestResponse.builder()
            .id("initial").type(PaymentType.INITIAL).status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("5000000"))
            .lineItems(List.of(PaymentLineItemResponse.builder().type("RENT").displayName("Tiền thuê tháng 1")
                .quantity(1).unitPrice(new BigDecimal("5000000")).amount(new BigDecimal("5000000")).build())).build());
        when(payments.toResponse(initial, "tenant")).thenReturn(PaymentRequestResponse.builder()
                .lineItems(List.of(PaymentLineItemResponse.builder().type("RENT")
                        .amount(new BigDecimal("5000000")).build())).build());
        when(payments.createMonthlyPayment(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(PaymentRequest.builder().id("monthly-payment").build());
    }

    @Test
    void firstPeriodSettlesMetersAndExtrasWithoutChargingRentTwice() {
        MonthlyInvoiceResponse result = service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"),
                        List.of(new IssueMonthlyInvoiceRequest.ExtraCharge("Sửa khóa", new BigDecimal("20000")))));
        assertEquals(new BigDecimal("5227000"), result.totalAmount());
        assertEquals(List.of("RENT", "ELECTRICITY", "WATER", "EXTRA"),
                result.lines().stream().map(l -> l.type()).toList());
        assertEquals("Tiền phòng tháng 2 (01/11/2026–30/11/2026)", result.lines().get(0).description());
        assertEquals(2, result.billingPeriod().paymentPeriodNumber());
        assertEquals("RECURRING", result.billingPeriod().kind());
        assertEquals("monthly-payment", result.paymentRequestId());
        assertEquals(MonthlyInvoiceStatus.UNPAID, result.status());
        assertEquals(Instant.parse("2026-11-05T16:59:59Z"), result.dueAt());
    }

    @Test void threeMonthRentCycleStoresCoverageAndDoesNotCollectSameRentNextMonth() {
        var revision=revisions.findById("revision").orElseThrow();
        revision.setFinancialSnapshot("{\"amountValue\":\"5000000\",\"amountNumber\":\"5.000.000 VNĐ/tháng\",\"paymentCycleMonths\":3,\"paymentDueOffsetDays\":7}");
        var first=service.issue("invoice","landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("150"),new BigDecimal("12"),List.of()));
        var rent=first.lines().stream().filter(l->l.type().equals("RENT")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("3"),rent.quantity());assertEquals(new BigDecimal("15000000"),rent.amount());
        assertEquals(LocalDate.of(2026,11,1),invoice.getRentPeriodStart());assertEquals(LocalDate.of(2027,2,1),invoice.getRentPeriodEndExclusive());
        assertEquals(Instant.parse("2026-11-08T16:59:59Z"),invoice.getDueAt());
        var previous=MonthlyInvoice.builder().id("previous").contractId("contract").periodIndex(0).periodStart(LocalDate.of(2026,10,1)).periodEndExclusive(LocalDate.of(2026,11,1))
                .rentPeriodStart(invoice.getRentPeriodStart()).rentPeriodEndExclusive(invoice.getRentPeriodEndExclusive()).status(MonthlyInvoiceStatus.PAID)
                .electricityEnd(new BigDecimal("150")).waterEnd(new BigDecimal("12")).lineItemsSnapshot(invoice.getLineItemsSnapshot()).build();
        invoice.setPeriodIndex(1);invoice.setPeriodStart(LocalDate.of(2026,11,1));invoice.setPeriodEndExclusive(LocalDate.of(2026,12,1));invoice.setStatus(MonthlyInvoiceStatus.DRAFT);
        invoice.setRentPeriodStart(null);invoice.setRentPeriodEndExclusive(null);
        when(invoices.findByContractIdAndPeriodIndex("contract",0)).thenReturn(Optional.of(previous));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice,previous));
        when(time.today()).thenReturn(LocalDate.of(2026,11,30));when(time.now()).thenReturn(Instant.parse("2026-11-30T03:00:00Z"));
        var second=service.issue("invoice","landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("150"),new BigDecimal("12"),List.of()));
        assertTrue(second.lines().stream().noneMatch(l->l.type().equals("RENT")));assertEquals("COSTS_ONLY",second.billingPeriod().kind());
    }

    @Test void signedAppendixUsesNewNextRentButOldExpensesAndFreezesBothSources() {
        ContractTermsResolver resolver = mock(ContractTermsResolver.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"terms",resolver);
        Contract root = contracts.findById("contract").orElseThrow();
        ContractRevision old = revisions.findById("revision").orElseThrow();
        ContractRevision next = ContractRevision.builder().id("appendix-revision")
                .financialSnapshot("{\"amountValue\":\"6000000\",\"amountNumber\":\"6.000.000 VNĐ/tháng\"}")
                .chargesSnapshot(old.getChargesSnapshot().replace("3500","4000").replace("3.500","4.000"))
                .initialMetersSnapshot(old.getInitialMetersSnapshot()).build();
        when(resolver.at(root,invoice.getPeriodStart())).thenReturn(old);
        when(resolver.at(root,invoice.getPeriodEndExclusive())).thenReturn(next);
        var result = service.issue("invoice","landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("150"),new BigDecimal("12"),List.of()));
        assertEquals(new BigDecimal("6000000"),result.lines().getFirst().amount());
        assertEquals(new BigDecimal("3500"),result.lines().stream().filter(l -> "ELECTRICITY".equals(l.type())).findFirst().orElseThrow().unitPrice());
        assertEquals("revision",invoice.getCostTermsRevisionId()); assertEquals("appendix-revision",invoice.getRentTermsRevisionId());
        assertEquals("revision",root.getCurrentRevisionId());
    }

    @Test void expiredContractStillIssuesItsFinalCostsButNotNextRent() {
        contracts.findById("contract").orElseThrow().setStatus(ContractStatus.EXPIRED);
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026,10,1)).leaseMonths(1).build()));
        var result=service.issue("invoice","landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("150"),new BigDecimal("12"),List.of()));
        assertEquals(new BigDecimal("207000"),result.totalAmount());
        assertTrue(result.lines().stream().noneMatch(l->"RENT".equals(l.type())));
    }
    // Exercise every monthly issue, including the final expense-only obligation.
    @Test void threeAndTwelveMonthsCollectExactlyRemainingRentThenOnlyFinalCosts() {
        for (int months : new int[]{3,12}) {
            var start=LocalDate.of(2026,10,13);
            when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder().moveInDate(start).leaseMonths(months).build()));
            BigDecimal collectedRent=new BigDecimal("5000000"); // Confirmed INITIAL from the fixture.
            for(int index=0;index<months;index++) {
                invoice.setPeriodIndex(index); invoice.setPeriodStart(start.plusMonths(index));
                invoice.setPeriodEndExclusive(start.plusMonths(index+1)); invoice.setStatus(MonthlyInvoiceStatus.DRAFT);
                when(time.today()).thenReturn(invoice.getPeriodEndExclusive());
                when(time.now()).thenReturn(invoice.getPeriodEndExclusive().atTime(10,0).atZone(BillingTime.ZONE).toInstant());
                if(index>0) when(invoices.findByContractIdAndPeriodIndex("contract",index-1)).thenReturn(Optional.of(
                    MonthlyInvoice.builder().periodIndex(index-1).status(MonthlyInvoiceStatus.PAID)
                        .electricityEnd(new BigDecimal("100")).waterEnd(new BigDecimal("10")).build()));
                var result=service.issue("invoice","landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("150"),new BigDecimal("12"),List.of()));
                collectedRent=collectedRent.add(result.lines().stream().filter(l->"RENT".equals(l.type())).map(InvoiceLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
                assertTrue(result.lines().stream().noneMatch(l->"DEPOSIT".equals(l.type())));
                if(index==months-1) {
                    assertEquals("FINAL_SETTLEMENT",result.billingPeriod().kind());
                    assertEquals(new BigDecimal("207000"),result.totalAmount());
                    assertNull(result.billingPeriod().paymentPeriodNumber());
                } else assertEquals(index+2,result.billingPeriod().paymentPeriodNumber());
            }
            assertEquals(new BigDecimal("5000000").multiply(BigDecimal.valueOf(months)),collectedRent);
        }
    }
    @Test void finalPeriodChargesUtilitiesServicesAndExtrasWithoutRentFour() {
        contracts.findById("contract").orElseThrow().setStatus(ContractStatus.EXPIRED);
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
            .moveInDate(LocalDate.of(2026,10,1)).leaseMonths(1).build()));
        var revision=revisions.findById("revision").orElseThrow();
        String baseCharges=revision.getChargesSnapshot();
        revision.setChargesSnapshot(baseCharges.substring(0,baseCharges.length()-1)
            +",{\"name\":\"Wifi\",\"billingMethod\":\"PER_MONTH\",\"estimatedMonthlyAmount\":90000,\"amountAndMethod\":\"90.000 VNĐ / tháng\"}]");
        var result=service.issue("invoice","landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("150"),new BigDecimal("12"),
            List.of(new IssueMonthlyInvoiceRequest.ExtraCharge("Chi phí phát sinh kỳ cuối",new BigDecimal("100000")))));
        assertEquals(new BigDecimal("397000"),result.totalAmount());
        assertEquals(List.of("SERVICE_FEE","ELECTRICITY","WATER","EXTRA"),result.lines().stream().map(InvoiceLine::type).toList());
        assertFalse(result.finalCostSupplement());assertTrue(result.lines().stream().noneMatch(l->"RENT".equals(l.type())));
    }

    @Test void signedRenewalAddsNextRentWithoutChangingOriginalRentalRequest() {
        contracts.findById("contract").orElseThrow().setEffectiveLeaseMonths(4);
        RentalRequest original=RentalRequest.builder().moveInDate(LocalDate.of(2026,10,1)).leaseMonths(1).build();
        when(rentals.findById("rental")).thenReturn(Optional.of(original));
        var result=service.issue("invoice","landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("150"),new BigDecimal("12"),List.of()));
        assertEquals(new BigDecimal("5207000"),result.totalAmount());
        assertTrue(result.lines().stream().anyMatch(l->"RENT".equals(l.type())));
        assertEquals(1,original.getLeaseMonths());
    }

    @Test void documentOnlyRenewalNeverCreatesSeparateMonthlyBills() {
        contracts.findById("contract").orElseThrow().setParentContractId("parent");
        assertThrows(AppException.class,()->service.issue("invoice","landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("150"),new BigDecimal("12"),List.of())));
        verify(payments,never()).createMonthlyPayment(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString(),any());
    }

    @Test
    void landlordCanIssueOnFinalDaySoTenantCanPayImmediately() {
        invoice.setPeriodStart(LocalDate.of(2026, 10, 5));
        invoice.setPeriodEndExclusive(LocalDate.of(2026, 11, 5));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026, 10, 5)).leaseMonths(12).build()));
        when(time.today()).thenReturn(LocalDate.of(2026, 11, 4));
        when(time.now()).thenReturn(Instant.parse("2026-11-03T17:01:00Z"));
        ContractRevision revision = revisions.findById("revision").orElseThrow();
        revision.setFinancialSnapshot("{\"amountValue\":\"5000000\",\"amountNumber\":\"5.000.000 VNĐ/tháng\",\"paymentDueOffsetDays\":4}");

        MonthlyInvoiceResponse result = service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"), List.of()));

        assertEquals(MonthlyInvoiceStatus.UNPAID, result.status());
        assertEquals(new BigDecimal("5207000"), result.totalAmount());
        assertEquals("Tiền phòng tháng 2 (05/11/2026–04/12/2026)", result.lines().get(0).description());
        assertEquals("monthly-payment", result.paymentRequestId());
        assertEquals(Instant.parse("2026-11-09T16:59:59Z"), result.dueAt());
    }

    @Test
    void landlordCannotIssueBeforeFinalDay() {
        invoice.setPeriodEndExclusive(LocalDate.of(2026, 11, 5));
        when(time.today()).thenReturn(LocalDate.of(2026, 11, 3));

        assertThrows(AppException.class, () -> service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"), List.of())));
        verify(payments, never()).createMonthlyPayment(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void tenantCannotSeeLandlordDraftButSeesIssuedInvoice() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));

        assertEquals(List.of("INITIAL_RENT"),service.list("contract", "tenant").stream().map(i->i.billingPeriod().kind()).toList());
        assertEquals(2, service.list("contract", "landlord").size());

        invoice.setStatus(MonthlyInvoiceStatus.UNPAID);
        assertEquals(2, service.list("contract", "tenant").size());
    }

    @Test
    void newContractPaymentWindowFollowsLeasePeriodRatherThanCalendarFifth() {
        invoice.setPeriodStart(LocalDate.of(2026, 10, 10));
        invoice.setPeriodEndExclusive(LocalDate.of(2026, 11, 10));
        when(time.today()).thenReturn(LocalDate.of(2026, 11, 10));
        when(time.now()).thenReturn(Instant.parse("2026-11-10T03:00:00Z"));
        ContractRevision revision = revisions.findById("revision").orElseThrow();
        revision.setFinancialSnapshot("{\"amountValue\":\"5000000\",\"amountNumber\":\"5.000.000 VNĐ/tháng\",\"paymentDueOffsetDays\":4}");

        MonthlyInvoiceResponse result = service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"), List.of()));

        assertEquals(Instant.parse("2026-11-14T16:59:59Z"), result.dueAt());
        assertEquals(Instant.parse("2026-11-19T17:00:00Z"),
                MonthlyBillingSchedule.actionRequiredAt(result.dueAt()));
    }

    @Test
    void firstPeriodCollectsFixedFeesAtPeriodEnd() {
        ContractRevision revision = revisions.findById("revision").orElseThrow();
        revision.setChargesSnapshot("[{\"name\":\"Wifi\",\"billingMethod\":\"PER_MONTH\",\"estimatedMonthlyAmount\":\"100000\",\"amountAndMethod\":\"100.000 VNĐ / tháng\"},"
                + "{\"name\":\"Điện\",\"billingMethod\":\"PER_KWH\",\"chargeType\":\"ELECTRICITY\",\"unitAmount\":\"3500\",\"amountAndMethod\":\"Tiền điện 3.500đ/kWh\"},"
                + "{\"name\":\"Nước\",\"billingMethod\":\"PER_M3\",\"chargeType\":\"WATER\",\"unitAmount\":\"16000\",\"amountAndMethod\":\"Tiền nước 16.000đ/m³\"}]");

        MonthlyInvoiceResponse result = service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"), List.of()));

        assertEquals(new BigDecimal("5307000"), result.totalAmount());
        assertEquals(List.of("RENT", "SERVICE_FEE", "ELECTRICITY", "WATER"),
                result.lines().stream().map(l -> l.type()).toList());
    }

    @Test
    void firstPeriodDoesNotChargeFeesAlreadyPaidInLegacyInitialPayment() {
        ContractRevision revision = revisions.findById("revision").orElseThrow();
        revision.setChargesSnapshot("[{\"name\":\"Wifi\",\"billingMethod\":\"PER_MONTH\",\"estimatedMonthlyAmount\":\"100000\",\"amountAndMethod\":\"100.000 VNĐ / tháng\"}]");
        PaymentRequest initial = payments.findByRentalRequestIdAndType("rental", PaymentType.INITIAL).orElseThrow();
        when(payments.toResponse(initial, "tenant")).thenReturn(PaymentRequestResponse.builder()
                .lineItems(List.of(PaymentLineItemResponse.builder().type("RENT")
                                .amount(new BigDecimal("5000000")).build(),
                        PaymentLineItemResponse.builder().type("SERVICE_FEE")
                                .amount(new BigDecimal("100000")).build())).build());

        MonthlyInvoiceResponse result = service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(null, null, List.of()));

        assertEquals(new BigDecimal("5000000"), result.totalAmount());
        assertEquals(List.of("RENT"), result.lines().stream().map(l -> l.type()).toList());
        assertEquals(MonthlyInvoiceStatus.UNPAID, result.status());
    }

    @Test
    void lowerMeterReadingIsRejectedWithoutCreatingPayment() {
        assertThrows(AppException.class, () -> service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("99"), new BigDecimal("12"), List.of())));
        verify(payments, never()).createMonthlyPayment(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void landlordCanPrepareMeterReadingsBeforeAutomaticIssue() {
        demoTime("2026-10-31T10:00:00");
        MonthlyInvoiceResponse prepared = service.prepare("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"),
                        List.of(new IssueMonthlyInvoiceRequest.ExtraCharge("Sửa khóa", new BigDecimal("20000")))));
        assertEquals(new BigDecimal("150"), prepared.electricityEnd());
        assertEquals(new BigDecimal("12"), prepared.waterEnd());
        assertEquals(1, prepared.draftExtraCharges().size());
        assertEquals(MonthlyInvoiceStatus.DRAFT, prepared.status());
        verify(payments, never()).createMonthlyPayment(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void scheduledSyncAutomaticallyIssuesPreparedInvoiceAtMidnight() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByStatus(eq(ContractStatus.ACTIVE), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(contract)));
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026, 10, 1)).leaseMonths(12).build()));
        when(invoices.findByContractIdAndPeriodIndex("contract", 0)).thenReturn(Optional.of(invoice));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        invoice.setElectricityEnd(new BigDecimal("150"));
        invoice.setWaterEnd(new BigDecimal("12"));
        invoice.setDraftExtraChargesSnapshot("[]");

        service.scheduledSync();

        assertEquals(MonthlyInvoiceStatus.UNPAID, invoice.getStatus());
        assertEquals(new BigDecimal("5207000"), invoice.getTotalAmount());
        verify(payments, times(1)).createMonthlyPayment(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void tenantCannotIssueAndIssuedInvoiceCannotBeIssuedAgain() {
        assertThrows(AppException.class, () -> service.issue("invoice", "tenant",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"), List.of())));
        invoice.setStatus(MonthlyInvoiceStatus.UNPAID);
        assertThrows(AppException.class, () -> service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"), List.of())));
    }

    @Test
    void secondPeriodAddsRentAndFixedFeesEvenIfPriorBillStillUnpaid() {
        invoice.setPeriodIndex(1);
        invoice.setPeriodStart(LocalDate.of(2026, 11, 1));
        invoice.setPeriodEndExclusive(LocalDate.of(2026, 12, 1));
        when(time.today()).thenReturn(LocalDate.of(2026, 12, 1));
        when(time.now()).thenReturn(Instant.parse("2026-12-01T03:00:00Z"));
        MonthlyInvoice previous = MonthlyInvoice.builder().contractId("contract").periodIndex(0)
                .status(MonthlyInvoiceStatus.OVERDUE).electricityEnd(new BigDecimal("150"))
                .waterEnd(new BigDecimal("12")).build();
        when(invoices.findByContractIdAndPeriodIndex("contract", 0)).thenReturn(Optional.of(previous));
        ContractRevision revision = revisions.findById("revision").orElseThrow();
        revision.setChargesSnapshot("[{\"name\":\"Phí dịch vụ\",\"billingMethod\":\"PER_MONTH\",\"estimatedMonthlyAmount\":\"150000\",\"amountAndMethod\":\"150.000 VNĐ / tháng\"},"
                + "{\"name\":\"Điện\",\"billingMethod\":\"PER_KWH\",\"chargeType\":\"ELECTRICITY\",\"unitAmount\":\"3500\",\"amountAndMethod\":\"Tiền điện 3.500đ/kWh\"},"
                + "{\"name\":\"Nước\",\"billingMethod\":\"PER_M3\",\"chargeType\":\"WATER\",\"unitAmount\":\"16000\",\"amountAndMethod\":\"Tiền nước 16.000đ/m³\"}]");

        MonthlyInvoiceResponse result = service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("160"), new BigDecimal("13"), List.of()));
        assertEquals(new BigDecimal("5201000"), result.totalAmount());
        assertEquals(List.of("RENT", "SERVICE_FEE", "ELECTRICITY", "WATER"),
                result.lines().stream().map(l -> l.type()).toList());
        assertEquals("Tiền phòng tháng 3 (01/12/2026–31/12/2026)", result.lines().get(0).description());
        assertEquals(MonthlyInvoiceStatus.OVERDUE, previous.getStatus());
    }

    @Test
    void finalLeasePeriodOnlySettlesActualExpensesWithoutExtraRent() {
        invoice.setPeriodIndex(11);
        invoice.setPeriodStart(LocalDate.of(2027, 9, 1));
        invoice.setPeriodEndExclusive(LocalDate.of(2027, 10, 1));
        when(time.today()).thenReturn(LocalDate.of(2027, 10, 1));
        when(time.now()).thenReturn(Instant.parse("2027-10-01T03:00:00Z"));
        MonthlyInvoice previous = MonthlyInvoice.builder().contractId("contract").periodIndex(10)
                .status(MonthlyInvoiceStatus.PAID).electricityEnd(new BigDecimal("100"))
                .waterEnd(new BigDecimal("10")).build();
        when(invoices.findByContractIdAndPeriodIndex("contract", 10)).thenReturn(Optional.of(previous));

        MonthlyInvoiceResponse result = service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"), List.of()));

        assertEquals(new BigDecimal("207000"), result.totalAmount());
        assertEquals(List.of("ELECTRICITY", "WATER"), result.lines().stream().map(l -> l.type()).toList());
    }

    @Test
    void monthEndPeriodsAreAnchoredToOriginalMoveInAndRestartDoesNotDuplicate() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        when(time.today()).thenReturn(LocalDate.of(2026, 5, 1));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026, 1, 31)).leaseMonths(3).build()));
        when(invoices.findByContractIdAndPeriodIndex(eq("contract"), anyInt())).thenReturn(Optional.empty());
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of());
        List<MonthlyInvoice> created = new ArrayList<>();
        when(invoices.saveAndFlush(any())).thenAnswer(invocation -> {
            MonthlyInvoice item = invocation.getArgument(0);
            created.add(item);
            return item;
        });

        service.syncContract("contract");
        assertEquals(List.of(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30)), created.stream().map(MonthlyInvoice::getPeriodEndExclusive).toList());

        created.clear();
        when(invoices.findByContractIdAndPeriodIndex(eq("contract"), anyInt())).thenAnswer(invocation ->
                Optional.of(MonthlyInvoice.builder().periodIndex(invocation.getArgument(1)).build()));
        service.syncContract("contract");
        assertTrue(created.isEmpty());
    }

    @Test
    void overdueInvoiceRemainsSeparateAndCanBeSettledAfterConfirmation() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026, 12, 1)).leaseMonths(1).build()));
        invoice.setStatus(MonthlyInvoiceStatus.UNPAID);
        invoice.setPaymentRequestId("monthly-payment");
        invoice.setDueAt(Instant.parse("2026-10-30T00:00:00Z"));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.AWAITING_TRANSFER).build()));
        service.syncContract("contract");
        assertEquals(MonthlyInvoiceStatus.OVERDUE, invoice.getStatus());
        verify(payments).markMonthlyOverdue(eq("invoice"), any());

        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.CONFIRMED).confirmedAt(Instant.parse("2026-11-02T00:00:00Z")).build()));
        service.syncContract("contract");
        assertEquals(MonthlyInvoiceStatus.PAID, invoice.getStatus());
    }

    @Test
    void overdueFeeIncreasesSameInvoiceAndPaymentOnlyOncePerDay() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026, 12, 1)).leaseMonths(1).build()));
        ContractRevision revision = revisions.findById("revision").orElseThrow();
        revision.setPoliciesSnapshot("{\"latePaymentFeeMode\":\"FIXED_PER_DAY\",\"latePaymentFeeAmount\":10000,\"latePaymentFeeGraceDays\":0}");
        invoice.setStatus(MonthlyInvoiceStatus.UNPAID);
        invoice.setPaymentRequestId("monthly-payment");
        invoice.setBaseAmount(new BigDecimal("100000"));
        invoice.setTotalAmount(new BigDecimal("100000"));
        invoice.setLineItemsSnapshot("[]");
        invoice.setDueAt(Instant.parse("2026-11-05T16:59:59Z"));
        when(time.now()).thenReturn(Instant.parse("2026-11-07T03:00:00Z"));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.AWAITING_TRANSFER).build()));
        when(payments.increaseMonthlyPayment(eq("invoice"), eq(new BigDecimal("120000")), anyString()))
                .thenReturn(true);

        service.syncContract("contract");
        service.syncContract("contract");

        assertEquals(new BigDecimal("20000"), invoice.getLateFeeAmount());
        assertEquals(new BigDecimal("120000"), invoice.getTotalAmount());
        assertEquals(MonthlyInvoiceStatus.OVERDUE, invoice.getStatus());
        verify(payments, times(1)).increaseMonthlyPayment(eq("invoice"), eq(new BigDecimal("120000")), anyString());
    }

    @Test
    void landlordRecordsFiveDayOverdueActionAndTenantAcknowledgesWithoutEndingLease() {
        invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
        invoice.setDueAt(Instant.parse("2026-11-05T16:59:59Z"));
        when(time.now()).thenReturn(Instant.parse("2026-11-10T17:01:00Z"));
        when(time.today()).thenReturn(LocalDate.of(2026, 11, 10));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));

        MonthlyInvoiceResponse recorded = service.recordOverdueAction("invoice", "landlord",
                new CreateOverdueActionRequest(OverdueAction.Type.EXTENSION_PROPOSAL,
                        "Đề xuất trả trước ngày 15/11; vui lòng phản hồi.", LocalDate.of(2026, 11, 15)));
        assertEquals(1, recorded.overdueActions().size());
        String actionId = recorded.overdueActions().get(0).id();
        MonthlyInvoiceResponse acknowledged = service.acknowledgeOverdueAction("invoice", actionId,
                "tenant", new AcknowledgeOverdueActionRequest("Tôi đã xem và sẽ liên hệ lại."));

        assertNotNull(acknowledged.overdueActions().get(0).acknowledgedAt());
        assertEquals(ContractStatus.ACTIVE, contracts.findById("contract").orElseThrow().getStatus());
        assertEquals(Instant.parse("2026-11-05T16:59:59Z"), invoice.getDueAt());
        assertEquals(MonthlyInvoiceStatus.OVERDUE, invoice.getStatus());
        assertThrows(AppException.class, () -> service.acknowledgeOverdueAction("invoice", actionId,
                "tenant", new AcknowledgeOverdueActionRequest("Lần hai")));
    }

    @Test
    void overdueActionRequiresFiveDaysAndCannotBeRecordedDuringPaymentReview() {
        invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
        invoice.setDueAt(Instant.parse("2026-11-05T16:59:59Z"));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));
        CreateOverdueActionRequest request = new CreateOverdueActionRequest(
                OverdueAction.Type.PAYMENT_REQUEST, "Đề nghị kiểm tra và thanh toán khoản nợ.", null);
        assertThrows(AppException.class, () -> service.recordOverdueAction("invoice", "landlord", request));

        when(time.now()).thenReturn(Instant.parse("2026-11-10T17:01:00Z"));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.TRANSFER_REPORTED).build()));
        assertThrows(AppException.class, () -> service.recordOverdueAction("invoice", "landlord", request));
        assertThrows(AppException.class, () -> service.recordOverdueAction("invoice", "tenant", request));
        verify(invoices, never()).save(invoice);
    }

    @Test
    void deferredDebtIsCarriedOnceAndOldPaymentIsCancelledWhenNextBillIssues() {
        MonthlyInvoice previous = MonthlyInvoice.builder().id("previous").contractId("contract")
                .periodIndex(0).status(MonthlyInvoiceStatus.OVERDUE)
                .electricityEnd(new BigDecimal("150")).waterEnd(new BigDecimal("12"))
                .totalAmount(new BigDecimal("3865000"))
                .deferredAt(Instant.parse("2026-11-10T00:00:00Z")).build();
        invoice.setPeriodIndex(1);
        invoice.setPeriodStart(LocalDate.of(2026, 11, 1));
        invoice.setPeriodEndExclusive(LocalDate.of(2026, 12, 1));
        when(time.today()).thenReturn(LocalDate.of(2026, 12, 1));
        when(time.now()).thenReturn(Instant.parse("2026-12-01T03:00:00Z"));
        when(invoices.findByContractIdAndPeriodIndex("contract", 0)).thenReturn(Optional.of(previous));
        when(payments.findByInvoiceId("previous")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));

        MonthlyInvoiceResponse result = service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("160"), new BigDecimal("13"), List.of()));

        assertEquals(MonthlyInvoiceStatus.ROLLED_OVER, previous.getStatus());
        assertEquals("invoice", previous.getRolledToInvoiceId());
        assertEquals(new BigDecimal("8916000"), result.totalAmount());
        assertEquals(1, result.lines().stream().filter(l -> "BALANCE_FORWARD".equals(l.type())).count());
        verify(payments).cancelMonthlyForRollover("previous");
    }

    @Test
    void ownerCanFreezeDebtAfterFiveDaysButNotInFinalLeasePeriod() {
        invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
        invoice.setDueAt(Instant.parse("2026-11-05T16:59:59Z"));
        invoice.setTotalAmount(new BigDecimal("3865000"));
        when(time.now()).thenReturn(Instant.parse("2026-11-13T17:01:00Z"));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));

        MonthlyInvoiceResponse result = service.deferToNextPeriod("invoice", "landlord");
        assertNotNull(result.deferredAt());
        assertEquals(new BigDecimal("3865000"), result.totalAmount());

        invoice.setDeferredAt(null);
        invoice.setPeriodIndex(11);
        assertThrows(AppException.class, () -> service.deferToNextPeriod("invoice", "landlord"));
    }

    @Test
    void mutualTerminationNeedsTenantConsentAndHandoverBeforeDepositAndListingChange() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
        invoice.setDueAt(Instant.parse("2026-11-05T16:59:59Z"));
        when(time.now()).thenReturn(Instant.parse("2026-11-11T03:00:00Z"));
        when(time.today()).thenReturn(LocalDate.of(2026, 11, 10));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));
        DepositRecord deposit = DepositRecord.builder().originalAmount(new BigDecimal("5000000"))
                .heldAmount(new BigDecimal("5000000")).refundableAmount(new BigDecimal("5000000"))
                .status(DepositStatus.HELD).build();
        when(deposits.findByRentalRequestIdForUpdate("rental")).thenReturn(Optional.of(deposit));
        when(deposits.findByRentalRequestId("rental")).thenReturn(Optional.of(deposit));

        service.proposeMutualTermination("invoice", "landlord");
        assertEquals(ContractStatus.ACTIVE, contract.getStatus());
        verifyNoInteractions(listingStatusService);
        assertThrows(AppException.class, () -> service.completeMutualTermination("invoice", "landlord",
                new CompleteTerminationRequest(true, true)));
        assertThrows(AppException.class, () -> service.acceptMutualTermination("invoice", "tenant",
                new AcceptTerminationRequest(true, false, true)));
        service.acceptMutualTermination("invoice", "tenant", new AcceptTerminationRequest(true, true, true));
        assertThrows(AppException.class, () -> service.completeMutualTermination("invoice", "landlord",
                new CompleteTerminationRequest(true, false)));
        service.completeMutualTermination("invoice", "landlord", new CompleteTerminationRequest(true, true));

        assertEquals(ContractStatus.TERMINATED, contract.getStatus());
        assertEquals(DepositStatus.RETAINED_BY_LANDLORD, deposit.getStatus());
        assertEquals(BigDecimal.ZERO, deposit.getRefundableAmount());
        verify(listingStatusService).releaseRentedAfterTermination("listing", "landlord");
        verify(parkingReservations).releaseReservationsForContract("contract");
    }

    @Test
    void tenantMayDeclineTerminationAndContractStaysActive() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
        invoice.setDueAt(Instant.parse("2026-11-05T16:59:59Z"));
        when(time.now()).thenReturn(Instant.parse("2026-11-13T17:01:00Z"));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));
        DepositRecord deposit = DepositRecord.builder().originalAmount(new BigDecimal("5000000"))
                .heldAmount(new BigDecimal("5000000")).status(DepositStatus.HELD).build();
        when(deposits.findByRentalRequestId("rental")).thenReturn(Optional.of(deposit));

        service.proposeMutualTermination("invoice", "landlord");
        service.declineMutualTermination("invoice", "tenant");

        assertEquals(ContractStatus.ACTIVE, contract.getStatus());
        assertNotNull(contract.getTerminationDeclinedAt());
        assertThrows(AppException.class, () -> service.completeMutualTermination("invoice", "landlord",
                new CompleteTerminationRequest(true, true)));
        verifyNoInteractions(listingStatusService);
    }

    @Test
    void landlordMayWithdrawAfterTenantDeclinesWithoutChangingLeaseOrDeposit() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
        invoice.setDueAt(Instant.parse("2026-11-05T16:59:59Z"));
        when(time.now()).thenReturn(Instant.parse("2026-11-13T17:01:00Z"));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));
        DepositRecord deposit = DepositRecord.builder().originalAmount(new BigDecimal("5000000"))
                .heldAmount(new BigDecimal("5000000")).status(DepositStatus.HELD).build();
        when(deposits.findByRentalRequestId("rental")).thenReturn(Optional.of(deposit));

        service.proposeMutualTermination("invoice", "landlord");
        service.declineMutualTermination("invoice", "tenant");
        service.withdrawMutualTermination("invoice", "landlord");

        assertNotNull(contract.getTerminationDeclinedAt());
        assertNotNull(contract.getTerminationCancelledAt());
        assertEquals(ContractStatus.ACTIVE, contract.getStatus());
        assertEquals(DepositStatus.HELD, deposit.getStatus());
        verifyNoInteractions(listingStatusService);
    }

    @Test
    void landlordCanForceAfterDeclineOnlyWithSignedClauseAndVacantPossession() {
        Contract contract = contracts.findById("contract").orElseThrow();
        contract.setSignedAt(Instant.parse("2026-10-05T06:00:00Z"));
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
        invoice.setDueAt(Instant.parse("2026-11-05T16:59:59Z"));
        invoice.setPaymentRequestId("monthly-payment");
        when(time.now()).thenReturn(Instant.parse("2026-11-13T17:01:00Z"));
        when(time.today()).thenReturn(LocalDate.of(2026, 11, 14));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));
        DepositRecord deposit = DepositRecord.builder().originalAmount(new BigDecimal("5000000"))
                .heldAmount(new BigDecimal("5000000")).status(DepositStatus.HELD).build();
        when(deposits.findByRentalRequestId("rental")).thenReturn(Optional.of(deposit));
        when(deposits.findByRentalRequestIdForUpdate("rental")).thenReturn(Optional.of(deposit));

        service.proposeMutualTermination("invoice", "landlord");
        service.declineMutualTermination("invoice", "tenant");
        ForceTerminationRequest confirmed = new ForceTerminationRequest(true, true, true, true);
        assertThrows(AppException.class, () -> service.forceTerminationAfterDecline("invoice", "landlord", confirmed));
        ContractRevision revision = revisions.findById("revision").orElseThrow();
        revision.setPoliciesSnapshot("{\"overdueLandlordTerminationAfterFiveDays\":true}");
        revision.setSpecialTerms("bên cho thuê vẫn có thể thực hiện quyền chấm dứt đã thỏa thuận");
        assertThrows(AppException.class, () -> service.forceTerminationAfterDecline("invoice", "landlord",
                new ForceTerminationRequest(true, true, false, true)));
        assertThrows(AppException.class, () -> service.forceTerminationAfterDecline("invoice", "tenant", confirmed));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.TRANSFER_REPORTED).build()));
        assertThrows(AppException.class, () -> service.forceTerminationAfterDecline("invoice", "landlord", confirmed));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));
        verifyNoInteractions(listingStatusService);

        MonthlyInvoiceResponse result = service.forceTerminationAfterDecline("invoice", "landlord", confirmed);
        assertNotNull(result.terminationForcedAt());
        assertEquals(ContractStatus.TERMINATED, contract.getStatus());
        assertEquals(DepositStatus.RETAINED_BY_LANDLORD, deposit.getStatus());
        verify(listingStatusService).releaseRentedAfterTermination("listing", "landlord");
        verify(parkingReservations).releaseReservationsForContract("contract");
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.CONFIRMED).confirmedAt(Instant.parse("2026-11-14T01:00:00Z")).build()));
        service.list("contract", "tenant");
        assertEquals(MonthlyInvoiceStatus.PAID, invoice.getStatus());
    }

    @Test
    void landlordCanWithdrawProposalBeforeConsent() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
        invoice.setDueAt(Instant.parse("2026-11-05T16:59:59Z"));
        when(time.now()).thenReturn(Instant.parse("2026-11-13T17:01:00Z"));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.OVERDUE).build()));
        DepositRecord deposit = DepositRecord.builder().originalAmount(new BigDecimal("5000000"))
                .heldAmount(new BigDecimal("5000000")).status(DepositStatus.HELD).build();
        when(deposits.findByRentalRequestId("rental")).thenReturn(Optional.of(deposit));

        service.proposeMutualTermination("invoice", "landlord");
        service.withdrawMutualTermination("invoice", "landlord");

        assertNotNull(contract.getTerminationCancelledAt());
        assertEquals(ContractStatus.ACTIVE, contract.getStatus());
        assertThrows(AppException.class, () -> service.acceptMutualTermination("invoice", "tenant",
                new AcceptTerminationRequest(true, true, true)));
        verifyNoInteractions(listingStatusService);
    }

    @Test
    void payingOverdueInvoiceCancelsUnfinishedTerminationProposal() {
        Contract contract = contracts.findById("contract").orElseThrow();
        contract.setTerminationProposalInvoiceId("invoice");
        contract.setTerminationProposedAt(Instant.parse("2026-11-10T00:00:00Z"));
        when(time.now()).thenReturn(Instant.parse("2026-11-11T00:00:00Z"));
        when(time.today()).thenReturn(LocalDate.of(2026, 11, 11));
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
        invoice.setPaymentRequestId("monthly-payment");
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.CONFIRMED).confirmedAt(Instant.parse("2026-11-11T00:00:00Z")).build()));

        service.syncContract("contract");

        assertEquals(MonthlyInvoiceStatus.PAID, invoice.getStatus());
        assertNotNull(contract.getTerminationCancelledAt());
        verifyNoInteractions(listingStatusService);
    }

    private void demoTime(String local) {
        var value = java.time.LocalDateTime.parse(local).atZone(BillingTime.ZONE);
        when(time.now()).thenReturn(value.toInstant()); when(time.today()).thenReturn(value.toLocalDate());
    }
    private void demoIssued() {
        invoice.setStatus(MonthlyInvoiceStatus.UNPAID);
        invoice.setPeriodStart(LocalDate.of(2026,12,12)); invoice.setPeriodEndExclusive(LocalDate.of(2027,1,12));
        invoice.setDueAt(Instant.parse("2027-01-16T16:59:59Z")); invoice.setOriginalDueAt(invoice.getDueAt());
        invoice.setPaymentRequestId("monthly-payment");
        invoice.setBaseAmount(new BigDecimal("1000000")); invoice.setTotalAmount(new BigDecimal("1000000"));
        invoice.setLineItemsSnapshot("[{\"type\":\"RENT\",\"description\":\"Tiền thuê\",\"quantity\":1,\"unitPrice\":1000000,\"amount\":1000000}]");
        revisions.findById("revision").orElseThrow().setPoliciesSnapshot(
                "{\"latePaymentFeeMode\":\"FIXED_PER_DAY\",\"latePaymentFeeAmount\":100000,\"latePaymentFeeGraceDays\":0}");
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder().status(PaymentStatus.OVERDUE).build()));
        when(payments.increaseMonthlyPayment(anyString(), any(), anyString())).thenReturn(true);
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder().moveInDate(LocalDate.of(2026,12,12)).leaseMonths(12).build()));
        when(invoices.findByContractIdAndPeriodIndex("contract",0)).thenReturn(Optional.of(invoice));
    }
    private PaymentExtensionPayload extension(int days) { return new PaymentExtensionPayload(days, "Xin trả lương rồi thanh toán."); }

    private void octoberThreeMonthDemo() {
        demoIssued();
        invoice.setPeriodStart(LocalDate.of(2026, 10, 12));
        invoice.setPeriodEndExclusive(LocalDate.of(2026, 11, 12));
        invoice.setDueAt(Instant.parse("2026-11-16T16:59:59Z"));
        invoice.setOriginalDueAt(invoice.getDueAt());
        invoice.setElectricityEnd(new BigDecimal("1550"));
        invoice.setWaterEnd(new BigDecimal("90"));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026, 10, 12)).leaseMonths(3).build()));
    }

    @Test void idleInvoiceAutoDefersOnlyAfterTenCompleteDaysAndRepeatingSyncIsIdempotent() {
        octoberThreeMonthDemo();
        demoTime("2026-11-26T23:59:59");
        var tenthDay = service.list("contract", "landlord").get(0);
        assertNull(tenthDay.deferredAt());
        assertEquals(new BigDecimal("1000000"), tenthDay.lateFeeAmount());
        demoTime("2026-11-27T00:00:00");
        var automatic = service.list("contract", "landlord").get(0);
        assertEquals("ROLLOVER", automatic.activeResolution());
        assertEquals("DEFERRED", automatic.workflowState());
        assertEquals(new BigDecimal("2000000"), automatic.totalAmount());
        var feeLine = automatic.lines().stream().filter(line -> "LATE_FEE".equals(line.type())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("10"), feeLine.quantity());
        assertEquals(new BigDecimal("100000"), feeLine.unitPrice());
        assertEquals("ngày", feeLine.quantityUnit());
        assertFalse(automatic.canRequestExtension());
        assertFalse(automatic.canResolveOverdue());
        assertEquals(OverdueAction.Type.AUTOMATIC_ROLLOVER, automatic.overdueActions().get(0).type());
        assertEquals("SYSTEM", automatic.overdueActions().get(0).createdBy());
        demoTime("2026-12-01T10:00:00");
        var repeated = service.list("contract", "tenant").get(0);
        assertEquals(automatic.deferredAt(), repeated.deferredAt());
        assertEquals(1, repeated.overdueActions().size());
        assertEquals(new BigDecimal("1000000"), repeated.lateFeeAmount());
        verify(payments, times(1)).increaseMonthlyPayment(eq("invoice"), eq(new BigDecimal("2000000")), anyString());
        verify(payments, never()).cancelMonthlyForRollover(anyString());
        assertThrows(AppException.class, () -> service.extendPaymentDirectly("invoice", "landlord", extension(7)));
        verifyNoInteractions(listingStatusService, parkingReservations);
        verify(deposits, never()).save(any());
    }

    @Test void schedulerResumesLateAndCapsFeeBeforeAutomaticallyFreezingDebt() {
        octoberThreeMonthDemo(); demoTime("2026-12-01T10:00:00");
        var contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByStatus(eq(ContractStatus.ACTIVE), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(contract)));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service.scheduledSync(); service.scheduledSync();
        assertNotNull(invoice.getDeferredAt());
        assertEquals(new BigDecimal("1000000"), invoice.getLateFeeAmount());
        assertEquals(1, service.list("contract", "tenant").get(0).overdueActions().size());
    }

    @Test void automaticRolloverCarriesDebtOnceAndCancelsOldQrOnlyWhenNextBillIssues() {
        octoberThreeMonthDemo(); demoTime("2026-11-27T00:01:00");
        service.syncContract("contract");
        MonthlyInvoice next = MonthlyInvoice.builder().id("next").contractId("contract").periodIndex(1)
                .periodStart(LocalDate.of(2026, 11, 12)).periodEndExclusive(LocalDate.of(2026, 12, 12))
                .status(MonthlyInvoiceStatus.DRAFT).totalAmount(BigDecimal.ZERO).build();
        when(invoices.findByIdForUpdate("next")).thenReturn(Optional.of(next));
        when(invoices.findByContractIdAndPeriodIndex("contract", 1)).thenReturn(Optional.of(next));
        demoTime("2026-12-12T00:01:00");
        var payload = new IssueMonthlyInvoiceRequest(new BigDecimal("1560"), new BigDecimal("91"), List.of());
        var issued = service.issue("next", "landlord", payload);
        assertEquals(new BigDecimal("7051000"), issued.totalAmount());
        assertEquals(1, issued.lines().stream().filter(line -> "BALANCE_FORWARD".equals(line.type())).count());
        assertEquals(MonthlyInvoiceStatus.ROLLED_OVER, invoice.getStatus());
        assertEquals("next", invoice.getRolledToInvoiceId());
        assertThrows(AppException.class, () -> service.issue("next", "landlord", payload));
        verify(payments, times(1)).cancelMonthlyForRollover("invoice");
    }

    @Test void finalPeriodAndAlreadyIssuedNextBillNeverGetAutomaticRolloverButFeesStayCapped() {
        octoberThreeMonthDemo(); demoTime("2026-12-01T10:00:00");
        invoice.setPeriodIndex(2);
        assertNull(service.list("contract", "landlord").get(0).deferredAt());
        assertEquals(new BigDecimal("1000000"), invoice.getLateFeeAmount());
        invoice.setPeriodIndex(0);
        when(invoices.findByContractIdAndPeriodIndex("contract", 1)).thenReturn(Optional.of(
                MonthlyInvoice.builder().status(MonthlyInvoiceStatus.UNPAID).build()));
        assertNull(service.list("contract", "landlord").get(0).deferredAt());
        assertTrue(service.list("contract", "landlord").get(0).overdueActions().isEmpty());
    }

    @Test void payingAutoDeferredInvoiceBeforeNextIssuePreventsDebtBeingChargedTwice() {
        octoberThreeMonthDemo(); demoTime("2026-11-27T00:01:00");
        service.syncContract("contract");
        Instant confirmationTime = time.now();
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.CONFIRMED).confirmedAt(confirmationTime).build()));
        service.syncContract("contract");
        assertEquals(MonthlyInvoiceStatus.PAID, invoice.getStatus());
        MonthlyInvoice next = MonthlyInvoice.builder().id("next").contractId("contract").periodIndex(1)
                .periodStart(LocalDate.of(2026, 11, 12)).periodEndExclusive(LocalDate.of(2026, 12, 12))
                .status(MonthlyInvoiceStatus.DRAFT).totalAmount(BigDecimal.ZERO).build();
        when(invoices.findByIdForUpdate("next")).thenReturn(Optional.of(next));
        when(invoices.findByContractIdAndPeriodIndex("contract", 1)).thenReturn(Optional.of(next));
        demoTime("2026-12-12T00:01:00");
        var issued = service.issue("next", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("1560"), new BigDecimal("91"), List.of()));
        assertEquals(new BigDecimal("5051000"), issued.totalAmount());
        assertTrue(issued.lines().stream().noneMatch(line -> "BALANCE_FORWARD".equals(line.type())));
        verify(payments, never()).cancelMonthlyForRollover("invoice");
    }

    @Test void autoRolloverWaitsForPendingExtensionThenExpiresAndBackfillsOnlyUpToTenDays() {
        octoberThreeMonthDemo(); demoTime("2026-11-25T10:00:00");
        service.requestPaymentExtension("invoice", "tenant", extension(7));
        demoTime("2026-11-27T00:01:00");
        var waiting = service.list("contract", "landlord").get(0);
        assertNull(waiting.deferredAt());
        assertEquals(new BigDecimal("900000"), waiting.lateFeeAmount());
        demoTime("2026-11-27T10:00:00");
        var expired = service.list("contract", "tenant").get(0);
        assertEquals(PaymentExtension.Status.EXPIRED, expired.paymentExtensions().get(0).status());
        assertNotNull(expired.deferredAt());
        assertEquals(new BigDecimal("1000000"), expired.lateFeeAmount());
    }

    @Test void paymentReviewDisputeAndPaidBillsAreNotAutomaticallyDeferred() {
        octoberThreeMonthDemo(); demoTime("2026-11-27T00:01:00");
        for (PaymentStatus status : List.of(PaymentStatus.TRANSFER_REPORTED, PaymentStatus.DISPUTED, PaymentStatus.CONFIRMED)) {
            when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder().status(status).build()));
            assertNull(service.list("contract", "landlord").get(0).deferredAt());
        }
        assertEquals(MonthlyInvoiceStatus.PAID, invoice.getStatus());
        verify(payments, never()).increaseMonthlyPayment(anyString(), any(), anyString());
    }

    @Test void existingTerminationProposalAndManualRolloverAreNotOverridden() {
        octoberThreeMonthDemo(); demoTime("2026-11-27T00:01:00");
        var contract = contracts.findById("contract").orElseThrow();
        contract.setTerminationProposalInvoiceId("invoice");
        contract.setTerminationProposedAt(Instant.parse("2026-11-22T03:00:00Z"));
        assertNull(service.list("contract", "landlord").get(0).deferredAt());
        contract.setTerminationCancelledAt(time.now());
        invoice.setDeferredAt(Instant.parse("2026-11-22T03:00:00Z"));
        invoice.setResolutionMode("ROLLOVER");
        assertTrue(service.list("contract", "landlord").get(0).overdueActions().isEmpty());
    }

    @Test void approvedExtensionResetsRolloverWindowAndPreservesFrozenPenalty() {
        octoberThreeMonthDemo(); demoTime("2026-11-19T10:00:00");
        var request = service.requestPaymentExtension("invoice", "tenant", extension(7)).paymentExtensions().get(0);
        demoTime("2026-11-20T10:00:00");
        service.decidePaymentExtension("invoice", request.id(), "landlord", true);
        demoTime("2026-11-27T00:01:00");
        assertNull(service.list("contract", "landlord").get(0).deferredAt());
        demoTime("2026-12-07T23:59:59");
        assertNull(service.list("contract", "landlord").get(0).deferredAt());
        demoTime("2026-12-08T00:00:00");
        var auto = service.list("contract", "landlord").get(0);
        assertNotNull(auto.deferredAt());
        assertEquals(new BigDecimal("300000"), auto.lateFeeAmount());
        assertTrue(auto.lateFeeExempt());
    }

    @Test void landlordCannotForgeSystemAutomaticRolloverHistory() {
        octoberThreeMonthDemo(); demoTime("2026-11-22T00:01:00");
        service.syncContract("contract");
        assertThrows(AppException.class, () -> service.recordOverdueAction("invoice", "landlord",
                new CreateOverdueActionRequest(OverdueAction.Type.AUTOMATIC_ROLLOVER,
                        "Hệ thống tự cộng dồn giả mạo.", null)));
    }

    @Test void manualTerminationDuringPendingRequestWinsWithoutNestedAutomaticRollover() {
        octoberThreeMonthDemo(); demoTime("2026-11-25T10:00:00");
        service.requestPaymentExtension("invoice", "tenant", extension(7));
        DepositRecord deposit = DepositRecord.builder().originalAmount(new BigDecimal("5000000"))
                .heldAmount(new BigDecimal("5000000")).status(DepositStatus.HELD).build();
        when(deposits.findByRentalRequestId("rental")).thenReturn(Optional.of(deposit));
        demoTime("2026-11-27T00:01:00");
        var proposal = service.proposeMutualTermination("invoice", "landlord");
        assertNull(proposal.deferredAt());
        assertEquals("TERMINATION", proposal.activeResolution());
        assertEquals(PaymentExtension.Status.REJECTED, proposal.paymentExtensions().get(0).status());
        assertEquals(new BigDecimal("1000000"), proposal.lateFeeAmount());
        var synced = service.list("contract", "landlord").get(0);
        assertNull(synced.deferredAt());
        assertTrue(synced.overdueActions().isEmpty());
        assertEquals("TERMINATION", synced.activeResolution());
    }

    @Test void legacyRolledOverLumpSumDisplaysTenDaysWithoutChangingStoredBillOrMoney() {
        octoberThreeMonthDemo(); demoTime("2026-12-12T00:01:00");
        invoice.setStatus(MonthlyInvoiceStatus.ROLLED_OVER);
        invoice.setLateFeeAmount(new BigDecimal("1000000"));
        invoice.setTotalAmount(new BigDecimal("2000000"));
        String oldSnapshot = "[{\"type\":\"LATE_FEE\",\"description\":\"Phí chậm thanh toán\",\"quantity\":1,\"unitPrice\":1000000,\"amount\":1000000}]";
        invoice.setLineItemsSnapshot(oldSnapshot);
        var result = service.list("contract", "tenant").get(0);
        var line = result.lines().get(0);
        assertEquals(new BigDecimal("10"), line.quantity());
        assertEquals(new BigDecimal("100000"), line.unitPrice());
        assertEquals("ngày", line.quantityUnit());
        assertEquals(new BigDecimal("1000000"), line.amount());
        assertEquals(new BigDecimal("2000000"), result.totalAmount());
        assertEquals(oldSnapshot, invoice.getLineItemsSnapshot());
        verify(payments, never()).increaseMonthlyPayment(anyString(), any(), anyString());
        verify(invoices, never()).save(invoice);
    }

    @Test void capDisplaysActualCalculationAndLegacyCappedAmountDoesNotInventDayCount() {
        octoberThreeMonthDemo(); demoTime("2026-11-26T23:59:59");
        revisions.findById("revision").orElseThrow().setPoliciesSnapshot(
                "{\"latePaymentFeeMode\":\"FIXED_PER_DAY\",\"latePaymentFeeAmount\":100000,\"latePaymentFeeGraceDays\":0,\"latePaymentFeeCap\":700000}");
        var line = service.list("contract", "landlord").get(0).lines().stream()
                .filter(row -> "LATE_FEE".equals(row.type())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("10"), line.quantity());
        assertEquals(new BigDecimal("100000"), line.unitPrice());
        assertEquals(new BigDecimal("700000"), line.amount());
        assertEquals("ngày", line.quantityUnit());
        invoice.setStatus(MonthlyInvoiceStatus.PAID);
        invoice.setLineItemsSnapshot("[{\"type\":\"LATE_FEE\",\"description\":\"Phí chậm thanh toán\",\"quantity\":1,\"unitPrice\":700000,\"amount\":700000}]");
        var legacy = service.list("contract", "tenant").get(0).lines().get(0);
        assertNull(legacy.quantityUnit());
        assertEquals(new BigDecimal("700000"), legacy.amount());
    }

    @Test void fixedOncePenaltyIsOneOccurrenceNotSeveralDays() {
        octoberThreeMonthDemo(); demoTime("2026-11-26T23:59:59");
        revisions.findById("revision").orElseThrow().setPoliciesSnapshot(
                "{\"latePaymentFeeMode\":\"FIXED_ONCE\",\"latePaymentFeeAmount\":100000,\"latePaymentFeeGraceDays\":0}");
        var line = service.list("contract", "landlord").get(0).lines().stream()
                .filter(row -> "LATE_FEE".equals(row.type())).findFirst().orElseThrow();
        assertEquals(BigDecimal.ONE, line.quantity());
        assertEquals(new BigDecimal("100000"), line.unitPrice());
        assertEquals(new BigDecimal("100000"), line.amount());
        assertEquals("lần", line.quantityUnit());
    }

    @ParameterizedTest
    @CsvSource({"FIXED_PER_DAY,FIXED_ONCE,300000,100000,100000", "FIXED_ONCE,FIXED_PER_DAY,100000,300000,1000000",
            "FIXED_PER_DAY,NONE,300000,0,0", "NONE,FIXED_PER_DAY,0,300000,1000000", "NONE,FIXED_ONCE,0,100000,100000"})
    void signedFeeModeAmendmentChangesFuturePeriodCalculationWithoutRepricingIssuedBill(
            String previousMode,String nextMode,String oldDayThree,String nextDayThree,String nextDayTen) throws Exception {
        var mapper=new ObjectMapper();
        var old=revisions.findById("revision").orElseThrow();
        old.setPoliciesSnapshot(mapper.writeValueAsString(LatePaymentPolicy.forNewAgreement(
                java.util.Map.of("latePaymentFeeMode",previousMode,"latePaymentFeeAmount",100000),java.util.Map.of())));
        var signedPolicy=old.getPoliciesSnapshot();
        var builder=new AmendmentTermsBuilder(mapper);
        // Exercise the same mode-only payload as the dropdown: a kept rate must still recalculate the cap.
        var proposed=builder.build(old,java.util.Map.of("freeForm",true,"fields",java.util.Map.of("policies",
                java.util.Map.of("latePaymentFeeMode",nextMode,"latePaymentFeeAmount",100000))));
        var next=ContractRevision.builder().id("fee-appendix").financialSnapshot(old.getFinancialSnapshot())
                .chargesSnapshot(old.getChargesSnapshot()).initialMetersSnapshot(old.getInitialMetersSnapshot())
                .policiesSnapshot(builder.write(proposed.terms().get("policies"))).build();
        when(revisions.findById(next.getId())).thenReturn(Optional.of(next));
        var history=mock(com.hs.contract.repository.ContractAmendmentRepository.class);
        var appendix=com.hs.contract.model.ContractAmendment.builder().contractId("contract").status("REQUESTED")
                .effectiveOn(LocalDate.of(2026,11,1)).termsRevisionId(next.getId()).build();
        when(history.findByContractIdOrderByCreatedAtDesc("contract")).thenReturn(List.of(appendix));
        var resolver=new ContractTermsResolver(history,revisions);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"terms",resolver);
        var root=contracts.findById("contract").orElseThrow();
        assertSame(old,resolver.at(root,LocalDate.of(2026,11,1)),"Proposal alone cannot change fees");
        appendix.setStatus("APPROVED");
        assertSame(old,resolver.at(root,LocalDate.of(2026,11,1)),"Consent without verified signatures is insufficient");
        appendix.setSignedAt(Instant.parse("2026-10-20T03:00:00Z"));appendix.setStatus("SCHEDULED");
        assertSame(old,resolver.at(root,LocalDate.of(2026,10,31)),"Signed future terms must not apply early");
        assertSame(next,resolver.at(root,LocalDate.of(2026,11,1)));
        appendix.setStatus("APPLIED");

        // November's bill settles October expenses, so its frozen cost policy remains the old one.
        var oldBill=invoice;
        when(invoices.findByContractIdAndPeriodIndex("contract",0)).thenReturn(Optional.of(oldBill));
        service.issue("invoice","landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("150"),new BigDecimal("12"),List.of()));
        assertEquals("revision",oldBill.getCostTermsRevisionId());
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(oldBill));
        when(payments.findByInvoiceId(anyString())).thenReturn(Optional.of(PaymentRequest.builder().status(PaymentStatus.OVERDUE).build()));
        when(payments.increaseMonthlyPayment(anyString(),any(),anyString())).thenReturn(true);
        demoTime("2026-11-08T10:00:00");
        var oldResponse=service.list("contract","tenant").getFirst();
        assertEquals(0,new BigDecimal(oldDayThree).compareTo(oldResponse.lateFeeAmount()==null?BigDecimal.ZERO:oldResponse.lateFeeAmount()));
        oldBill.setStatus(MonthlyInvoiceStatus.PAID);
        var oldTotal=oldBill.getTotalAmount();var oldLines=oldBill.getLineItemsSnapshot();

        // December's bill settles the period beginning on the effective date and uses the signed new mode.
        var nextBill=MonthlyInvoice.builder().id("next-fee-invoice").contractId("contract").periodIndex(1)
                .periodStart(LocalDate.of(2026,11,1)).periodEndExclusive(LocalDate.of(2026,12,1))
                .status(MonthlyInvoiceStatus.DRAFT).totalAmount(BigDecimal.ZERO).build();
        when(invoices.findByIdForUpdate(nextBill.getId())).thenReturn(Optional.of(nextBill));
        when(invoices.findByContractIdAndPeriodIndex("contract",1)).thenReturn(Optional.of(nextBill));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(nextBill,oldBill));
        demoTime("2026-12-01T10:00:00");
        var issued=service.issue(nextBill.getId(),"landlord",new IssueMonthlyInvoiceRequest(new BigDecimal("200"),new BigDecimal("14"),List.of()));
        assertEquals(next.getId(),nextBill.getCostTermsRevisionId());
        var base=issued.totalAmount();
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(nextBill));
        for(var step:List.of(new String[]{"2026-12-08T10:00:00",nextDayThree},new String[]{"2026-12-15T23:59:59",nextDayTen})) {
            demoTime(step[0]);var result=service.list("contract","tenant").getFirst();
            assertEquals(0,new BigDecimal(step[1]).compareTo(result.lateFeeAmount()==null?BigDecimal.ZERO:result.lateFeeAmount()));
            assertEquals(0,base.add(new BigDecimal(step[1])).compareTo(result.totalAmount()));
            var fees=result.lines().stream().filter(l->"LATE_FEE".equals(l.type())).toList();
            if(nextMode.equals("NONE"))assertTrue(fees.isEmpty());
            else {
                assertEquals(1,fees.size());assertEquals(nextMode.equals("FIXED_ONCE")?"lần":"ngày",fees.getFirst().quantityUnit());
                if(nextMode.equals("FIXED_ONCE"))assertEquals(BigDecimal.ONE,fees.getFirst().quantity());
            }
            var total=result.totalAmount();
            assertEquals(total,service.list("contract","landlord").getFirst().totalAmount(),"Repeated sync cannot add the fee twice");
        }
        if(nextMode.equals("FIXED_ONCE"))verify(payments,times(1)).increaseMonthlyPayment(eq(nextBill.getId()),any(),anyString());
        if(nextMode.equals("NONE"))verify(payments,never()).increaseMonthlyPayment(eq(nextBill.getId()),any(),anyString());
        assertEquals(oldTotal,oldBill.getTotalAmount());assertEquals(oldLines,oldBill.getLineItemsSnapshot());
        assertEquals("revision",root.getCurrentRevisionId());assertEquals(signedPolicy,old.getPoliciesSnapshot());
    }

    @Test void requestAfterThreeDaysFreezesFeeAt300kAndApprovalNeverAddsMoreEvenAfterNewDeadline() {
        demoIssued(); demoTime("2027-01-19T10:00:00");
        var request = service.requestPaymentExtension("invoice", "tenant", extension(7)).paymentExtensions().get(0);
        assertEquals(new BigDecimal("300000"), request.frozenLateFee());
        demoTime("2027-01-20T10:00:00");
        var approved = service.decidePaymentExtension("invoice", request.id(), "landlord", true);
        assertEquals(Instant.parse("2027-01-27T03:00:00Z"), approved.dueAt());
        assertEquals(new BigDecimal("300000"), approved.lateFeeAmount()); assertTrue(approved.lateFeeExempt());
        verify(payments).extendMonthlyDueDate("invoice", approved.dueAt(), "landlord");
        demoTime("2027-02-02T00:01:00");
        var expired = service.list("contract", "tenant").get(0);
        assertEquals(new BigDecimal("300000"), expired.lateFeeAmount()); assertTrue(expired.canResolveOverdue());
        assertEquals("NONE", expired.activeResolution());
        var frozenLine = expired.lines().stream().filter(line -> "LATE_FEE".equals(line.type())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("3"), frozenLine.quantity());
        assertEquals(new BigDecimal("100000"), frozenLine.unitPrice());
        assertEquals("ngày", frozenLine.quantityUnit());
    }
    @Test void requestBeforeDeadlineAndApprovalLeavesZeroPenaltyForever() {
        demoIssued(); demoTime("2027-01-12T10:00:00");
        var request = service.requestPaymentExtension("invoice", "tenant", extension(7)).paymentExtensions().get(0);
        assertEquals(BigDecimal.ZERO, request.frozenLateFee());
        demoTime("2027-01-13T10:00:00"); service.decidePaymentExtension("invoice", request.id(), "landlord", true);
        demoTime("2027-01-28T10:00:00");
        assertEquals(BigDecimal.ZERO, service.list("contract", "tenant").get(0).lateFeeAmount());
        verify(payments, never()).increaseMonthlyPayment(anyString(), any(), anyString());
    }
    @Test void rejectRequestBackfillsFromOriginalDueWithoutDoubleAdding() {
        demoIssued(); demoTime("2027-01-19T10:00:00");
        var request = service.requestPaymentExtension("invoice", "tenant", extension(7)).paymentExtensions().get(0);
        demoTime("2027-01-20T10:00:00");
        var rejected = service.decidePaymentExtension("invoice", request.id(), "landlord", false);
        assertEquals(new BigDecimal("400000"), rejected.lateFeeAmount());
        assertEquals(new BigDecimal("1400000"), rejected.totalAmount());
        assertEquals(rejected.lateFeeAmount(), service.list("contract", "tenant").get(0).lateFeeAmount());
        assertFalse(rejected.canRequestExtension());
    }
    @Test void expiredPendingRequestBackfillsAndCannotBeApprovedAfter48Hours() {
        demoIssued(); demoTime("2027-01-19T10:00:00");
        var request = service.requestPaymentExtension("invoice", "tenant", extension(7)).paymentExtensions().get(0);
        demoTime("2027-01-21T10:00:00");
        var result = service.list("contract", "tenant").get(0);
        assertEquals(PaymentExtension.Status.EXPIRED, result.paymentExtensions().get(0).status());
        assertEquals(new BigDecimal("500000"), result.lateFeeAmount());
        assertThrows(AppException.class, () -> service.decidePaymentExtension("invoice",request.id(),"landlord",true));
    }
    @Test void requestsValidateRangeOwnershipDuplicateAndPaymentReview() {
        demoIssued(); demoTime("2027-01-19T10:00:00");
        for (int days : new int[]{0,31,1000}) assertThrows(AppException.class,
                () -> service.requestPaymentExtension("invoice","tenant",extension(days)));
        assertThrows(AppException.class, () -> service.requestPaymentExtension("invoice","landlord",extension(7)));
        var first = service.requestPaymentExtension("invoice","tenant",extension(7));
        assertThrows(AppException.class, () -> service.requestPaymentExtension("invoice","tenant",extension(7)));
        assertThrows(AppException.class, () -> service.decidePaymentExtension("invoice",first.paymentExtensions().get(0).id(),"tenant",true));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder().status(PaymentStatus.TRANSFER_REPORTED).build()));
        assertThrows(AppException.class, () -> service.decidePaymentExtension("invoice",first.paymentExtensions().get(0).id(),"landlord",true));
    }
    @Test void threeOptionsUnlockOnlyAfterEndOfFifthDayAndCannotOverlap() {
        demoIssued(); demoTime("2027-01-21T23:59:59");
        assertFalse(service.list("contract","landlord").get(0).canResolveOverdue());
        assertThrows(AppException.class, () -> service.extendPaymentDirectly("invoice","landlord",extension(7)));
        demoTime("2027-01-22T00:00:00");
        var result = service.extendPaymentDirectly("invoice","landlord",extension(7));
        assertEquals("PAYMENT_EXTENSION", result.activeResolution());
        assertThrows(AppException.class, () -> service.deferToNextPeriod("invoice","landlord"));
        assertThrows(AppException.class, () -> service.proposeMutualTermination("invoice","landlord"));
        assertThrows(AppException.class, () -> service.extendPaymentDirectly("invoice","landlord",extension(7)));
    }
    @Test void rolloverClosesPendingRequestAndBackfillsFeeBeforeFreezingDebt() {
        demoIssued(); demoTime("2027-01-21T10:00:00");
        service.requestPaymentExtension("invoice","tenant",extension(7));
        demoTime("2027-01-22T00:00:00");
        var result = service.deferToNextPeriod("invoice","landlord");
        assertEquals("ROLLOVER", result.activeResolution()); assertEquals(new BigDecimal("600000"), result.lateFeeAmount());
        assertEquals(PaymentExtension.Status.REJECTED, result.paymentExtensions().get(0).status());
        assertFalse(result.canRequestExtension());
        assertThrows(AppException.class, () -> service.extendPaymentDirectly("invoice","landlord",extension(7)));
    }
    @Test void confirmedPaymentClosesPendingRequestWithoutChangingPaidBill() {
        demoIssued(); demoTime("2027-01-19T10:00:00");
        var request = service.requestPaymentExtension("invoice","tenant",extension(7)).paymentExtensions().get(0);
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                .status(PaymentStatus.CONFIRMED).confirmedAt(Instant.parse("2027-01-19T03:00:00Z")).build()));
        var result = service.list("contract","tenant").get(0);
        assertEquals(MonthlyInvoiceStatus.PAID,result.status());
        assertEquals(PaymentExtension.Status.CLOSED,result.paymentExtensions().get(0).status());
        assertThrows(AppException.class, () -> service.decidePaymentExtension("invoice",request.id(),"landlord",true));
    }
    @Test void locksContractThenInvoiceThenPaymentBeforeCreatingExtension() {
        demoIssued(); demoTime("2027-01-19T10:00:00");
        clearInvocations(contracts,invoices,payments);
        service.requestPaymentExtension("invoice","tenant",extension(30));
        var order = inOrder(contracts,invoices,payments);
        order.verify(invoices).findContractIdByInvoiceId("invoice");
        order.verify(contracts).findByIdForUpdate("contract");
        order.verify(invoices).findByIdForUpdate("invoice");
        order.verify(payments).lockMonthlyPayment("invoice");
        verify(invoices,never()).findById("invoice");
    }
    @Test void thirtyDaysIsAcceptedButNeverShortensExistingDeadline() {
        demoIssued(); demoTime("2027-01-12T10:00:00");
        var r = service.requestPaymentExtension("invoice","tenant",extension(1)).paymentExtensions().get(0);
        assertThrows(AppException.class,() -> service.decidePaymentExtension("invoice",r.id(),"landlord",true));
        demoTime("2027-01-22T00:00:00");
        var result = service.extendPaymentDirectly("invoice","landlord",extension(30));
        assertEquals(Instant.parse("2027-02-20T17:00:00Z"),result.dueAt());
    }
    /** Stateful repositories exercise actual catch-up order, including newly created drafts/payments. */
    private List<MonthlyInvoice> skippedMonthsDemo(String feeMode, ContractStatus contractStatus) {
        var contract = contracts.findById("contract").orElseThrow();
        contract.setStatus(contractStatus);
        when(contracts.findByStatus(eq(contractStatus), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(contract)));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026,10,12)).leaseMonths(3).build()));
        var revision = revisions.findById("revision").orElseThrow();
        revision.setFinancialSnapshot("{\"amountValue\":5000000,\"amountNumber\":\"5.000.000 VNĐ/tháng\",\"paymentDueOffsetDays\":4}");
        revision.setInitialMetersSnapshot("{\"electricityInitial\":1500,\"waterInitial\":85}");
        revision.setPoliciesSnapshot("{\"latePaymentFeeMode\":\"" + feeMode
                + "\",\"latePaymentFeeAmount\":100000,\"latePaymentFeeGraceDays\":0}");
        List<MonthlyInvoice> history = new ArrayList<>();
        var paymentHistory = new java.util.HashMap<String,PaymentRequest>();
        when(invoices.findByContractIdAndPeriodIndex(eq("contract"),anyInt())).thenAnswer(call ->
                history.stream().filter(i -> i.getPeriodIndex() == (Integer)call.getArgument(1)).findFirst());
        when(invoices.saveAndFlush(any())).thenAnswer(call -> {
            MonthlyInvoice created = call.getArgument(0);
            created.setId("bill-" + created.getPeriodIndex()); history.add(created); return created;
        });
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenAnswer(call ->
                history.stream().sorted(java.util.Comparator.comparingInt(MonthlyInvoice::getPeriodIndex).reversed()).toList());
        when(invoices.findByIdForUpdate(anyString())).thenAnswer(call ->
                history.stream().filter(i -> i.getId().equals(call.getArgument(0))).findFirst());
        when(payments.createMonthlyPayment(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString(),any()))
                .thenAnswer(call -> {
                    String id = call.getArgument(0);
                    var payment = PaymentRequest.builder().id("payment-" + id).invoiceId(id)
                            .status(PaymentStatus.AWAITING_TRANSFER).totalAmount(call.getArgument(6))
                            .dueAt(call.getArgument(8)).build();
                    paymentHistory.put(id,payment); return payment;
                });
        when(payments.findByInvoiceId(anyString())).thenAnswer(call -> Optional.ofNullable(paymentHistory.get(call.getArgument(0))));
        when(payments.increaseMonthlyPayment(anyString(),any(),anyString())).thenAnswer(call -> {
            paymentHistory.get(call.getArgument(0)).setTotalAmount(call.getArgument(1)); return true;
        });
        doAnswer(call -> { paymentHistory.get(call.getArgument(0)).setStatus(PaymentStatus.CANCELLED); return null; })
                .when(payments).cancelMonthlyForRollover(anyString());
        return history;
    }

    @Test void jumpingTwoMonthsWithoutAnyInteractionCarriesTenDayPenaltyIntoOnlyLatestBill() {
        var history = skippedMonthsDemo("FIXED_PER_DAY",ContractStatus.ACTIVE);
        demoTime("2026-12-12T00:01:00"); service.scheduledSync(); service.scheduledSync();
        var first = history.get(0); var second = history.get(1);
        assertEquals(2,history.size());
        assertEquals(MonthlyInvoiceStatus.ROLLED_OVER,first.getStatus());
        assertEquals(second.getId(),first.getRolledToInvoiceId());
        assertEquals(Instant.parse("2026-11-16T16:59:59Z"),first.getOriginalDueAt());
        assertEquals(new BigDecimal("1000000"),first.getLateFeeAmount());
        assertEquals(new BigDecimal("6000000"),first.getTotalAmount());
        assertEquals(new BigDecimal("11000000"),second.getTotalAmount());
        assertEquals(Instant.parse("2026-12-16T16:59:59Z"),second.getDueAt());
        assertEquals(MonthlyInvoiceStatus.UNPAID,second.getStatus());
        var firstResponse = service.list("contract","tenant").stream().filter(i -> i.id().equals(first.getId())).findFirst().orElseThrow();
        var penalty = firstResponse.lines().stream().filter(l -> "LATE_FEE".equals(l.type())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("10"),penalty.quantity()); assertEquals(new BigDecimal("100000"),penalty.unitPrice());
        assertEquals(1,firstResponse.overdueActions().size());
        assertEquals(1,service.list("contract","tenant").get(0).lines().stream().filter(l -> "BALANCE_FORWARD".equals(l.type())).count());
        verify(payments,times(1)).cancelMonthlyForRollover(first.getId());
        verify(payments,times(2)).createMonthlyPayment(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString(),any());
        verifyNoInteractions(listingStatusService,parkingReservations);
    }

    @Test void jumpingTwoMonthsWithFixedOncePolicyChargesOnePenaltyNotTen() {
        var history = skippedMonthsDemo("FIXED_ONCE",ContractStatus.ACTIVE);
        demoTime("2026-12-12T00:01:00"); service.scheduledSync(); service.scheduledSync();
        assertEquals(MonthlyInvoiceStatus.ROLLED_OVER,history.get(0).getStatus());
        assertEquals(new BigDecimal("100000"),history.get(0).getLateFeeAmount());
        assertEquals(new BigDecimal("10100000"),history.get(1).getTotalAmount());
        var penalty = service.list("contract","tenant").get(1).lines().stream().filter(l -> "LATE_FEE".equals(l.type())).findFirst().orElseThrow();
        assertEquals(BigDecimal.ONE,penalty.quantity()); assertEquals("lần",penalty.quantityUnit());
    }

    @Test void jumpingTwoMonthsWithoutPenaltyStillCarriesUnpaidRent() {
        var history = skippedMonthsDemo("NONE",ContractStatus.ACTIVE);
        demoTime("2026-12-12T00:01:00"); service.scheduledSync();
        assertEquals(MonthlyInvoiceStatus.ROLLED_OVER,history.get(0).getStatus());
        assertEquals(BigDecimal.ZERO,history.get(0).getLateFeeAmount());
        assertEquals(new BigDecimal("10000000"),history.get(1).getTotalAmount());
    }

    @Test void jumpingPastLeaseEndChainsAllDebtIntoFinalCostsWithoutRentFourOrAnotherBill() {
        var history = skippedMonthsDemo("FIXED_PER_DAY",ContractStatus.EXPIRED);
        demoTime("2027-01-27T00:01:00"); service.scheduledSync(); service.scheduledSync();
        assertEquals(3,history.size());
        assertEquals(MonthlyInvoiceStatus.ROLLED_OVER,history.get(0).getStatus());
        assertEquals(MonthlyInvoiceStatus.ROLLED_OVER,history.get(1).getStatus());
        var last = service.list("contract","tenant").get(0);
        assertEquals(MonthlyInvoiceStatus.OVERDUE,last.status());
        assertNull(last.deferredAt());
        assertEquals(new BigDecimal("13000000"),last.totalAmount());
        assertEquals(new BigDecimal("1000000"),last.lateFeeAmount());
        assertTrue(last.lines().stream().noneMatch(l -> "RENT".equals(l.type())));
        assertEquals(1,last.lines().stream().filter(l -> "BALANCE_FORWARD".equals(l.type())).count());
        verify(payments,times(2)).cancelMonthlyForRollover(anyString());
        verifyNoInteractions(listingStatusService,parkingReservations);
    }

    @Test void catchUpDoesNotCarryAlreadyReportedTransfer() {
        var history = skippedMonthsDemo("FIXED_PER_DAY",ContractStatus.ACTIVE);
        demoTime("2026-11-12T00:01:00"); service.scheduledSync();
        payments.findByInvoiceId(history.get(0).getId()).orElseThrow().setStatus(PaymentStatus.TRANSFER_REPORTED);
        demoTime("2026-12-12T00:01:00"); service.scheduledSync();
        assertEquals(2,history.size()); assertNull(history.get(0).getDeferredAt());
        assertEquals(new BigDecimal("5000000"),history.get(1).getTotalAmount());
        verify(payments,never()).cancelMonthlyForRollover(anyString());
    }

    @Test void catchUpDoesNotOverrideApprovedExtensionWithFutureDeadline() {
        var history = skippedMonthsDemo("FIXED_PER_DAY",ContractStatus.ACTIVE);
        demoTime("2026-11-12T00:01:00"); service.scheduledSync();
        demoTime("2026-11-15T10:00:00");
        var request = service.requestPaymentExtension(history.get(0).getId(),"tenant",extension(30)).paymentExtensions().get(0);
        service.decidePaymentExtension(history.get(0).getId(),request.id(),"landlord",true);
        demoTime("2026-12-12T00:01:00"); service.scheduledSync();
        assertNull(history.get(0).getDeferredAt());
        assertEquals(Instant.parse("2026-12-15T03:00:00Z"),history.get(0).getDueAt());
        assertEquals(new BigDecimal("5000000"),history.get(1).getTotalAmount());
        verify(payments,never()).cancelMonthlyForRollover(anyString());
    }

    @Test void midnightAutoFillsMissingReadingsOnlyAndNeverDuplicatesIssuedInvoice() {
        var contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByStatus(eq(ContractStatus.ACTIVE),any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(contract)));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));
        when(invoices.findByContractIdAndPeriodIndex("contract",0)).thenReturn(Optional.of(invoice));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder().moveInDate(LocalDate.of(2026,12,12)).leaseMonths(12).build()));
        invoice.setPeriodStart(LocalDate.of(2026,12,12)); invoice.setPeriodEndExclusive(LocalDate.of(2027,1,12));
        invoice.setElectricityEnd(new BigDecimal("150"));
        demoTime("2027-01-11T23:59:59"); service.scheduledSync(); assertEquals(MonthlyInvoiceStatus.DRAFT, invoice.getStatus());
        demoTime("2027-01-12T00:00:00"); service.scheduledSync(); service.scheduledSync();
        assertEquals(MonthlyInvoiceStatus.UNPAID, invoice.getStatus());
        assertEquals(new BigDecimal("150"), invoice.getElectricityEnd()); assertFalse(invoice.isElectricityAutoFilled());
        assertEquals(new BigDecimal("10"), invoice.getWaterEnd()); assertTrue(invoice.isWaterAutoFilled());
        verify(payments,times(1)).createMonthlyPayment(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString(),any());
    }
}
