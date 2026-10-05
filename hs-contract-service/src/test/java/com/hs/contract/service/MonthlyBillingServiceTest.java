package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.IssueMonthlyInvoiceRequest;
import com.hs.contract.dto.billing.MonthlyInvoiceResponse;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.model.MonthlyInvoice;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.ContractRepository;
import com.hs.contract.repository.ContractRevisionRepository;
import com.hs.contract.repository.MonthlyInvoiceRepository;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.dto.PaymentLineItemResponse;
import com.hs.payment.dto.PaymentRequestResponse;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.service.PaymentRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private MonthlyBillingService service;
    private MonthlyInvoice invoice;

    @BeforeEach
    void setUp() {
        service = new MonthlyBillingService(contracts, revisions, rentals, invoices,
                payments, new ObjectMapper(), time, tx);
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
        when(contracts.findById("contract")).thenReturn(Optional.of(contract));
        when(revisions.findById("revision")).thenReturn(Optional.of(revision));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder().build()));
        when(invoices.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PaymentRequest initial = PaymentRequest.builder().id("initial").status(PaymentStatus.CONFIRMED).build();
        when(payments.findByRentalRequestIdAndType("rental", PaymentType.INITIAL))
                .thenReturn(Optional.of(initial));
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
        assertEquals(new BigDecimal("227000"), result.totalAmount());
        assertEquals(List.of("ELECTRICITY", "WATER", "EXTRA"),
                result.lines().stream().map(l -> l.type()).toList());
        assertEquals("monthly-payment", result.paymentRequestId());
        assertEquals(MonthlyInvoiceStatus.UNPAID, result.status());
        assertEquals(Instant.parse("2026-11-05T16:59:59Z"), result.dueAt());
    }

    @Test
    void landlordCanIssueOnFinalDaySoTenantCanPayImmediately() {
        invoice.setPeriodStart(LocalDate.of(2026, 10, 5));
        invoice.setPeriodEndExclusive(LocalDate.of(2026, 11, 5));
        when(time.today()).thenReturn(LocalDate.of(2026, 11, 4));
        when(time.now()).thenReturn(Instant.parse("2026-11-03T17:01:00Z"));
        ContractRevision revision = revisions.findById("revision").orElseThrow();
        revision.setFinancialSnapshot("{\"amountValue\":\"5000000\",\"amountNumber\":\"5.000.000 VNĐ/tháng\",\"paymentDueOffsetDays\":4}");

        MonthlyInvoiceResponse result = service.issue("invoice", "landlord",
                new IssueMonthlyInvoiceRequest(new BigDecimal("150"), new BigDecimal("12"), List.of()));

        assertEquals(MonthlyInvoiceStatus.UNPAID, result.status());
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

        assertTrue(service.list("contract", "tenant").isEmpty());
        assertEquals(1, service.list("contract", "landlord").size());

        invoice.setStatus(MonthlyInvoiceStatus.UNPAID);
        assertEquals(1, service.list("contract", "tenant").size());
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
        assertEquals(Instant.parse("2026-11-18T17:00:00Z"),
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

        assertEquals(new BigDecimal("307000"), result.totalAmount());
        assertEquals(List.of("SERVICE_FEE", "ELECTRICITY", "WATER"),
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

        assertEquals(BigDecimal.ZERO, result.totalAmount());
        assertTrue(result.lines().isEmpty());
        assertEquals(MonthlyInvoiceStatus.PAID, result.status());
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
    void scheduledSyncAutomaticallyIssuesPreparedInvoiceAtTenAm() {
        Contract contract = contracts.findById("contract").orElseThrow();
        when(contracts.findByStatus(eq(ContractStatus.ACTIVE), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(contract)));
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder()
                .moveInDate(LocalDate.of(2026, 10, 1)).leaseMonths(1).build()));
        when(invoices.findByContractIdAndPeriodIndex("contract", 0)).thenReturn(Optional.of(invoice));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        invoice.setElectricityEnd(new BigDecimal("150"));
        invoice.setWaterEnd(new BigDecimal("12"));
        invoice.setDraftExtraChargesSnapshot("[]");

        service.scheduledSync();

        assertEquals(MonthlyInvoiceStatus.UNPAID, invoice.getStatus());
        assertEquals(new BigDecimal("207000"), invoice.getTotalAmount());
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
        assertEquals(MonthlyInvoiceStatus.OVERDUE, previous.getStatus());
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
}
