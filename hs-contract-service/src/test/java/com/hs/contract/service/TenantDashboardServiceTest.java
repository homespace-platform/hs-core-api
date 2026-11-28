package com.hs.contract.service;

import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchAttentionResponse.Priority;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.dto.branch.BranchOverviewResponse.ExpiryStatus;
import com.hs.contract.dto.tenant.TenantDashboardResponse;
import com.hs.contract.dto.tenant.TenantDashboardResponse.Task;
import com.hs.contract.model.ContractAmendment;
import com.hs.contract.repository.TenantDashboardReadRepository;
import com.hs.contract.repository.TenantDashboardReadRepository.*;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantDashboardServiceTest {
    private static final String ME = "tenant";
    private static final String OTHER = "other";
    /** 01/12/2026 00:00 in Vietnam. */
    private static final Instant NOW = Instant.parse("2026-11-30T17:00:00Z");
    private static final Instant REAL = NOW.minus(Duration.ofDays(1));

    private final TenantDashboardReadRepository reads = mock();
    private final BillingTime time = mock();
    private final RentalOnboardingService onboarding = mock();
    private final MoneyTotals totals = mock();
    private final TenantDashboardService service = new TenantDashboardService(reads, time,
            new StorageProperties("b", "ap-southeast-1", Duration.ZERO, Duration.ZERO, null, null), onboarding,
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

    TenantDashboardServiceTest() {
        when(time.now()).thenReturn(NOW);
        when(reads.moneyTotals(eq(ME), eq(NOW), eq(REAL), any())).thenReturn(totals);
        when(totals.getOverdueAmount()).thenReturn(BigDecimal.ZERO);
        when(totals.getDueSoonAmount()).thenReturn(BigDecimal.ZERO);
        when(totals.getAwaitingAmount()).thenReturn(BigDecimal.ZERO);
        when(reads.refundTotals(eq(ME), any(), any())).thenReturn(java.util.Collections.singletonList(
                new Object[]{0L, BigDecimal.ZERO}));
        when(reads.countComplaints(ME)).thenReturn(java.util.Collections.singletonList(new Object[]{0L, 0L}));
        when(reads.totalHeldDeposits(ME)).thenReturn(BigDecimal.ZERO);
    }

    private TenantDashboardResponse run() { return service.summary(ME, 12, REAL); }

    private static PaymentRequest pay(String id, PaymentType type, PaymentStatus status, long amount, Instant dueAt) {
        return PaymentRequest.builder().id(id).type(type).status(status).contractId("c1").rentalRequestId("rr")
                .totalAmount(BigDecimal.valueOf(amount)).dueAt(dueAt).build();
    }

    private static LeaseRow lease(String id, LocalDate moveIn, int months, LocalDate early) {
        return new LeaseRow(id, "HD-" + id, "l-" + id, "landlord", early, null, null, moveIn, months,
                BigDecimal.valueOf(3_000_000));
    }

    @Test
    void spendingUsesVietnamMonthsGroupsDepositSeparatelyAndFillsEmptyMonths() {
        var rows = List.of(
                new SpendRow(Instant.parse("2026-11-30T16:59:59Z"), PaymentType.MONTHLY_RENT, BigDecimal.valueOf(3)),
                new SpendRow(Instant.parse("2026-11-30T17:00:00Z"), PaymentType.MONTHLY_RENT, BigDecimal.valueOf(4)),
                new SpendRow(Instant.parse("2026-11-30T17:00:00Z"), PaymentType.INITIAL, BigDecimal.valueOf(10)),
                new SpendRow(Instant.parse("2026-12-05T00:00:00Z"), PaymentType.DEPOSIT_ADJUSTMENT, BigDecimal.valueOf(5)),
                new SpendRow(Instant.parse("2026-12-06T00:00:00Z"), PaymentType.UTILITY, BigDecimal.valueOf(1)),
                new SpendRow(Instant.parse("2026-12-06T00:00:00Z"), PaymentType.OTHER_CHARGE, BigDecimal.valueOf(2)));
        var s = TenantDashboardService.spending(rows, YearMonth.of(2026, 12), 12, 2);
        assertThat(s.months()).hasSize(12);
        assertThat(s.months().getFirst().month()).isEqualTo("2026-01");
        assertThat(s.months().getFirst().total()).isEqualByComparingTo("0");
        var nov = s.months().get(10);
        var dec = s.months().get(11);
        assertThat(nov.month()).isEqualTo("2026-11");
        assertThat(nov.total()).isEqualByComparingTo("3"); // 23:59:59 on 30/11 stays in November
        assertThat(dec.month()).isEqualTo("2026-12");
        assertThat(dec.byGroup().get("DEPOSIT_FIRST")).isEqualByComparingTo("15");
        assertThat(dec.byGroup().get("RENT")).isEqualByComparingTo("4"); // 00:00:00 on 01/12 is December
        assertThat(dec.byGroup().get("UTILITY_OTHER")).isEqualByComparingTo("3");
        assertThat(s.total()).isEqualByComparingTo("25");
        assertThat(s.totalExcludingDeposit()).isEqualByComparingTo("10");
        assertThat(s.receiptPdfIssues()).isEqualTo(2);
    }

    @Test
    void spendingWindowAndRowsOutsideItAreIgnored() {
        assertThat(TenantDashboardService.windowStart(YearMonth.of(2026, 12), 12))
                .isEqualTo(Instant.parse("2025-12-31T17:00:00Z"));
        assertThat(TenantDashboardService.windowStart(YearMonth.of(2026, 12), 6))
                .isEqualTo(Instant.parse("2026-06-30T17:00:00Z"));
        var s = TenantDashboardService.spending(List.of(new SpendRow(Instant.parse("2025-06-01T00:00:00Z"),
                PaymentType.MONTHLY_RENT, BigDecimal.TEN)), YearMonth.of(2026, 12), 6, 0);
        assertThat(s.total()).isEqualByComparingTo("0");
        assertThat(s.months()).hasSize(6);
    }

    @Test
    void requestCountsGroupCancelledAndAlwaysListEveryGroup() {
        var counts = TenantDashboardService.requestCounts(List.of(new Object[]{RentalRequestStatus.PENDING, 2L},
                new Object[]{RentalRequestStatus.CANCELLED_BY_RENTER, 1L},
                new Object[]{RentalRequestStatus.CANCELLED_BY_OWNER, 2L},
                new Object[]{RentalRequestStatus.CANCELLED_BY_SYSTEM, 3L}));
        assertThat(counts).containsExactly(Map.entry("PENDING", 2L), Map.entry("ACCEPTED", 0L),
                Map.entry("REJECTED", 0L), Map.entry("CANCELLED", 6L), Map.entry("EXPIRED", 0L),
                Map.entry("COMPLETED", 0L));
    }

    @Test
    void savedSplitsAvailableFromUnavailable() {
        var saved = TenantDashboardService.saved(List.of(new Object[]{ListingStatus.PUBLISHED, 3L},
                new Object[]{ListingStatus.RENTED, 2L}, new Object[]{ListingStatus.HIDDEN, 1L}));
        assertThat(saved.total()).isEqualTo(6);
        assertThat(saved.available()).isEqualTo(3);
        assertThat(saved.unavailable()).isEqualTo(3);
    }

    @Test
    void priorityBandsAreTwentyFourHoursAndThreeDays() {
        assertThat(TenantDashboardService.priority(NOW.minusSeconds(1), NOW)).isEqualTo(Priority.URGENT);
        assertThat(TenantDashboardService.priority(NOW.plus(Duration.ofHours(24)), NOW)).isEqualTo(Priority.URGENT);
        assertThat(TenantDashboardService.priority(NOW.plus(Duration.ofHours(24)).plusSeconds(1), NOW))
                .isEqualTo(Priority.SOON);
        assertThat(TenantDashboardService.priority(NOW.plus(Duration.ofDays(3)), NOW)).isEqualTo(Priority.SOON);
        assertThat(TenantDashboardService.priority(NOW.plus(Duration.ofDays(3)).plusSeconds(1), NOW))
                .isEqualTo(Priority.NORMAL);
        assertThat(TenantDashboardService.priority(null, NOW)).isEqualTo(Priority.NORMAL);
    }

    @Test
    void expiryBandsMatchTheLandlordView() {
        assertThat(TenantDashboardService.expiry(30)).isEqualTo(ExpiryStatus.WITHIN_30);
        assertThat(TenantDashboardService.expiry(31)).isEqualTo(ExpiryStatus.WITHIN_60);
        assertThat(TenantDashboardService.expiry(60)).isEqualTo(ExpiryStatus.WITHIN_60);
        assertThat(TenantDashboardService.expiry(61)).isEqualTo(ExpiryStatus.LONG);
    }

    @Test
    void overdueFollowsTheRowClockAndStoredOverdueWithFutureDueDateIsNotLate() {
        var late = pay("late", PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, 100, NOW.minusSeconds(3600));
        // Stored OVERDUE but extended into the future.
        var extended = pay("extended", PaymentType.MONTHLY_RENT, PaymentStatus.OVERDUE, 50, NOW.plus(Duration.ofDays(2)));
        // Utility runs on the real clock: late by billing time, still due by real time.
        var utility = pay("utility", PaymentType.UTILITY, PaymentStatus.AWAITING_TRANSFER, 7, NOW.minus(Duration.ofHours(12)));
        var reported = pay("reported", PaymentType.MONTHLY_RENT, PaymentStatus.TRANSFER_REPORTED, 900, NOW.minusSeconds(60));
        reported.setConfirmationDueAt(NOW.plusSeconds(60));
        var notOpen = pay("notOpen", PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, 800, NOW.plusSeconds(60));
        notOpen.setOpensAt(NOW.plusSeconds(1));
        when(reads.findDashboardPayments(eq(ME), eq(NOW), eq(REAL), any(), any())).thenReturn(List.of(late, extended, utility, reported, notOpen));
        when(totals.getOverdueCount()).thenReturn(1L);
        when(totals.getOverdueAmount()).thenReturn(new BigDecimal("100"));
        when(totals.getDueSoonAmount()).thenReturn(new BigDecimal("57"));
        when(totals.getAwaitingCount()).thenReturn(1L);
        when(totals.getAwaitingAmount()).thenReturn(new BigDecimal("900"));

        var p = run().payables();
        assertThat(p.overdueCount()).isEqualTo(1);
        assertThat(p.overdueAmount()).isEqualByComparingTo("100");
        assertThat(p.items()).extracting(i -> i.paymentId()).containsExactly("late", "utility", "extended");
        assertThat(p.items().get(1).clock()).isEqualTo(Clock.REAL);
        assertThat(p.items().get(1).overdue()).isFalse();
        assertThat(p.next().paymentId()).isEqualTo("utility");
        assertThat(p.dueSoonAmount()).isEqualByComparingTo("57");
        assertThat(p.awaiting().count()).isEqualTo(1);
        assertThat(p.awaiting().amount()).isEqualByComparingTo("900");
        assertThat(p.awaiting().items().getFirst().confirmationDueAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void rejectedReasonIsCutAndOnlyShownForRejectedRows() {
        var rejected = pay("r", PaymentType.MONTHLY_RENT, PaymentStatus.REJECTED, 1, NOW.plusSeconds(10));
        rejected.setRejectedReason("x".repeat(300));
        var open = pay("o", PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, 1, NOW.plusSeconds(20));
        open.setRejectedReason("stale");
        when(reads.findDashboardPayments(eq(ME), eq(NOW), eq(REAL), any(), any())).thenReturn(List.of(rejected, open));
        var items = run().payables().items();
        assertThat(items.get(0).rejectedReason()).hasSize(160);
        assertThat(items.get(1).rejectedReason()).isNull();
    }

    @Test
    void refundsToMeAreSeparateAndTheReportedOneIsATask() {
        var waiting = pay("refund1", PaymentType.DEPOSIT_REFUND, PaymentStatus.AWAITING_TRANSFER, 7, NOW.plusSeconds(100));
        var reported = pay("refund2", PaymentType.DEPOSIT_REFUND, PaymentStatus.TRANSFER_REPORTED, 3, NOW.plusSeconds(100));
        reported.setConfirmationDueAt(NOW.plus(Duration.ofHours(10)));
        when(reads.findIncomingRefunds(eq(ME), any(), any(), any())).thenReturn(List.of(waiting, reported));
        when(reads.refundTotals(eq(ME), any(), any())).thenReturn(java.util.Collections.singletonList(
                new Object[]{2L, BigDecimal.TEN}));
        var r = run();
        assertThat(r.payables().refundsIncoming().count()).isEqualTo(2);
        assertThat(r.payables().refundsIncoming().amount()).isEqualByComparingTo("10");
        assertThat(r.payables().items()).isEmpty();
        assertThat(r.tasks().items()).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo("CONFIRM_REFUND");
            assertThat(t.priority()).isEqualTo(Priority.URGENT);
            assertThat(t.clock()).isEqualTo(Clock.BILLING);
        });
    }

    @Test
    void holdsShowOnlyWithOpenMoneyAndTurnCriticalAtFifteenMinutes() {
        var onboarding = pay("p", PaymentType.RESERVATION_DEPOSIT, PaymentStatus.AWAITING_TRANSFER, 9, NOW.plusSeconds(1));
        onboarding.setRentalRequestId("hold-critical");
        var second = pay("p2", PaymentType.INITIAL, PaymentStatus.AWAITING_TRANSFER, 5, NOW.plusSeconds(1));
        second.setRentalRequestId("hold-late");
        var unrelatedRent = pay("p3", PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, 5, NOW.plusSeconds(1));
        unrelatedRent.setRentalRequestId("hold-paid");
        when(reads.findDashboardPayments(eq(ME), eq(NOW), eq(REAL), any(), any())).thenReturn(List.of(onboarding, second, unrelatedRent));
        when(reads.findHoldPayments(eq(ME), any(), any())).thenReturn(List.of(onboarding, second, unrelatedRent));
        when(reads.findHolds(eq(ME), eq(NOW), any())).thenReturn(List.of(
                new HoldRow("hold-critical", "l", NOW.plus(Duration.ofMinutes(15))),
                new HoldRow("hold-late", "l", NOW.plus(Duration.ofMinutes(16))),
                new HoldRow("hold-paid", "l", NOW.plus(Duration.ofMinutes(5)))));
        var holds = run().holds();
        assertThat(holds).extracting(h -> h.rentalRequestId()).containsExactly("hold-critical", "hold-late");
        assertThat(holds.get(0).critical()).isTrue();
        assertThat(holds.get(0).remainingSeconds()).isEqualTo(900);
        assertThat(holds.get(0).amountDue()).isEqualByComparingTo("9");
        assertThat(holds.get(1).critical()).isFalse();
    }

    @Test
    void leaseEndUsesTheSharedFormulaAndLeasesAreSortedByEnd() {
        var plain = lease("a", LocalDate.of(2026, 1, 1), 12, null);      // 2027-01-01
        var early = lease("b", LocalDate.of(2026, 1, 1), 12, LocalDate.of(2026, 12, 21)); // agreed early end wins
        when(reads.findLeases(eq(ME), any())).thenReturn(List.of(plain, early));
        when(reads.countLeases(ME)).thenReturn(2L);
        var leases = run().leases();
        assertThat(leases.activeCount()).isEqualTo(2);
        assertThat(leases.items()).extracting(l -> l.contractId()).containsExactly("b", "a");
        assertThat(leases.items().get(0).endExclusive()).isEqualTo(LocalDate.of(2026, 12, 21));
        assertThat(leases.items().get(0).daysRemaining()).isEqualTo(20);
        assertThat(leases.items().get(0).expiry()).isEqualTo(ExpiryStatus.WITHIN_30);
        assertThat(leases.items().get(1).endExclusive()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(leases.items().get(1).expiry()).isEqualTo(ExpiryStatus.WITHIN_60);
    }

    @Test
    void nextPaymentIsPerLeaseAndGlobal() {
        when(reads.findLeases(eq(ME), any())).thenReturn(List.of(lease("c1", LocalDate.of(2026, 1, 1), 24, null),
                lease("c2", LocalDate.of(2026, 1, 1), 24, null)));
        var a = pay("a", PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, 1, NOW.plus(Duration.ofDays(5)));
        var b = pay("b", PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, 1, NOW.plus(Duration.ofDays(2)));
        b.setContractId("c2");
        var later = pay("later", PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, 1, NOW.plus(Duration.ofDays(9)));
        when(reads.findDashboardPayments(eq(ME), eq(NOW), eq(REAL), any(), any())).thenReturn(List.of(a, b, later));
        var r = run();
        assertThat(r.payables().next().paymentId()).isEqualTo("b");
        var byId = r.leases().items().stream().collect(java.util.stream.Collectors.toMap(l -> l.contractId(), l -> l));
        assertThat(byId.get("c1").nextPayment().paymentId()).isEqualTo("a");
        assertThat(byId.get("c2").nextPayment().paymentId()).isEqualTo("b");
    }

    @Test
    void signingTasksByKindAndPassedDeadlineIsNoLongerATask() {
        when(reads.findSigningContracts(eq(ME), any())).thenReturn(List.of(
                new SignRow("lease", "HD-1", "rr1", null, null, NOW.plus(Duration.ofHours(5))),
                new SignRow("deposit", "HD-2", "rr2", null, "DEPOSIT", NOW.plus(Duration.ofDays(2))),
                new SignRow("annex", "HD-3", "rr3", "lease", "AMENDMENT", NOW.plus(Duration.ofDays(10))),
                new SignRow("missed", "HD-4", "rr4", null, null, NOW.minusSeconds(1))));
        var tasks = run().tasks();
        assertThat(tasks.items()).extracting(Task::type).containsExactly("SIGN_LEASE", "SIGN_DEPOSIT", "SIGN_ANNEX");
        assertThat(tasks.items()).extracting(Task::priority)
                .containsExactly(Priority.URGENT, Priority.SOON, Priority.NORMAL);
        assertThat(tasks.total()).isEqualTo(3);
        assertThat(tasks.urgentCount()).isEqualTo(1);
        assertThat(tasks.items().get(0).href()).isEqualTo("/dashboard/tenant/contracts/lease?tab=sign");
    }

    @Test
    void amendmentTaskOnlyWhenItWaitsForTheTenant() {
        when(reads.findLeases(eq(ME), any())).thenReturn(List.of(lease("c1", LocalDate.of(2026, 1, 1), 24, null)));
        var toMe = amendment("a1", "REQUESTED", "landlord", ME);
        var fromMeWaiting = amendment("a2", "REQUESTED", ME, "landlord");
        var changesForMe = amendment("a3", "CHANGES_REQUESTED", ME, "landlord");
        var applied = amendment("a4", "APPLIED", "landlord", ME);
        when(reads.findAmendments(eq(List.of("c1")), any())).thenReturn(List.of(toMe, fromMeWaiting, changesForMe, applied));
        assertThat(run().tasks().items()).extracting(Task::key)
                .containsExactlyInAnyOrder("AMENDMENT_RESPONSE:a1", "AMENDMENT_RESPONSE:a3");
        assertThat(run().waiting().items()).extracting(w -> w.type()).containsExactly("AMENDMENT_WAITING");
    }

    @Test
    void earlyExitSignatureAndComplaintTasksFollowTheirOwnClocks() {
        when(reads.findLeases(eq(ME), any())).thenReturn(List.of(lease("c1", LocalDate.of(2026, 1, 1), 24, null)));
        when(reads.findProposedEarlyExits(List.of("c1"))).thenReturn(List.of(
                new ExitRow("e1", "c1", "landlord", NOW.plus(Duration.ofDays(1))),
                new ExitRow("e2", "c1", ME, NOW.plus(Duration.ofDays(1)))));
        // Real clock is a day behind billing time: expires 2h after real now.
        when(reads.findSignaturesToConfirm(eq(ME), eq(REAL), any())).thenReturn(List.of(
                new SigRow("c1", REAL.plus(Duration.ofHours(2)))));
        when(reads.findOpenComplaints(eq(ME), any())).thenReturn(List.of(
                new ComplaintRow("d1", "c1", "rr", "PAYMENT", "OPEN", NOW.plus(Duration.ofDays(2)), ME),
                new ComplaintRow("d2", "c1", "rr", "PAYMENT", "OPEN", NOW.plus(Duration.ofDays(2)), "landlord")));
        when(reads.countComplaints(ME)).thenReturn(java.util.Collections.singletonList(new Object[]{2L, 1L}));
        var r = run();
        assertThat(r.tasks().items()).extracting(Task::key)
                .containsExactly("SIGN_CONFIRM_APP:c1", "TERMINATION_RESPONSE:e1", "COMPLAINT_RESPOND:d1");
        assertThat(r.tasks().items().get(0).clock()).isEqualTo(Clock.REAL);
        assertThat(r.complaints().openCount()).isEqualTo(2);
        assertThat(r.complaints().dueFromMeCount()).isEqualTo(1);
        assertThat(r.complaints().items()).extracting(c -> c.responseDueFor()).containsExactly("YOU", "OTHER");
    }

    @Test
    void leaseExpiringTaskOnlyInsideThirtyDaysAndBeforeTheEnd() {
        when(reads.findLeases(eq(ME), any())).thenReturn(List.of(
                lease("soon", LocalDate.of(2026, 1, 1), 12, LocalDate.of(2026, 12, 31)),
                lease("tomorrow", LocalDate.of(2026, 1, 1), 12, LocalDate.of(2026, 12, 2)),
                lease("today", LocalDate.of(2026, 1, 1), 12, LocalDate.of(2026, 12, 1)),
                lease("far", LocalDate.of(2026, 1, 1), 12, LocalDate.of(2027, 1, 1))));
        assertThat(run().tasks().items()).extracting(Task::key)
                .containsExactly("LEASE_EXPIRING:tomorrow", "LEASE_EXPIRING:soon");
    }

    @Test
    void everyBlockIsReadForTheCallerOnly() {
        run();
        verify(reads).findLeases(eq(ME), any());
        verify(reads).findDashboardPayments(eq(ME), eq(NOW), eq(REAL), any(), any());
        verify(reads).findSpending(eq(ME), any(), any());
        verify(reads).findOpenComplaints(eq(ME), any());
        verify(reads).countSavedByListingStatus(ME);
        verify(reads, never()).findLeases(eq(OTHER), any());
        // No ids to look up, so no batch lookups at all.
        verify(reads, never()).findListingInfo(any());
        verify(reads, never()).findAmendments(any(), any());
    }

    @Test
    void onboardingUsesExistingNextActionAndDoesNotRepeatOpenPayables() {
        var view = mock(RentalOnboardingService.View.class);
        when(reads.findAcceptedRequests(eq(ME), any())).thenReturn(List.of("rr"));
        when(onboarding.get("rr", ME)).thenReturn(view);
        when(view.nextAction()).thenReturn(new RentalOnboardingService.NextAction("TENANT",
                RentalOnboardingService.NextActionCode.RESPOND_DEFAULT_NOTICE, NOW.plusSeconds(60)));
        assertThat(run().tasks().items()).extracting(Task::type).containsExactly("RESPOND_DEFAULT_NOTICE");
        when(view.nextAction()).thenReturn(new RentalOnboardingService.NextAction("TENANT",
                RentalOnboardingService.NextActionCode.PAY_DEPOSIT, NOW.plusSeconds(60)));
        assertThat(run().tasks().items()).extracting(Task::type).containsExactly("PAY_DEPOSIT");
        when(reads.findDashboardPayments(eq(ME), eq(NOW), eq(REAL), any(), any())).thenReturn(List.of(
                pay("p", PaymentType.RESERVATION_DEPOSIT, PaymentStatus.AWAITING_TRANSFER, 9, NOW.plusSeconds(60))));
        assertThat(run().tasks().items()).isEmpty();
        when(view.nextAction()).thenReturn(new RentalOnboardingService.NextAction("LANDLORD",
                RentalOnboardingService.NextActionCode.CONFIRM_DEPOSIT, NOW.plusSeconds(60)));
        assertThat(run().tasks().items()).isEmpty();
    }

    @Test
    void leaseDatesRespectEffectiveAndAgreedMonthsAndNextPaymentSkipsOverdue() {
        when(reads.findLeases(eq(ME), any())).thenReturn(List.of(new LeaseRow("c1", "HD", "l", "owner",
                null, 10, 20, LocalDate.of(2026, 1, 1), 24, BigDecimal.ONE)));
        when(reads.findDashboardPayments(eq(ME), eq(NOW), eq(REAL), any(), any())).thenReturn(List.of(
                pay("past", PaymentType.MONTHLY_RENT, PaymentStatus.OVERDUE, 1, NOW.minusSeconds(1)),
                pay("future", PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, 1, NOW.plusSeconds(1))));
        var lease = run().leases().items().getFirst();
        assertThat(lease.endExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(lease.nextPayment().paymentId()).isEqualTo("future");
    }

    @Test
    void taskDetailsAreBoundedAndDeduplicatedWhileTotalKeepsAllSourceTasks() {
        var source = new java.util.ArrayList<SignRow>();
        for (int i = 0; i < 25; i++) source.add(new SignRow("c" + i, "HD", null, null, null, NOW.plusSeconds(60)));
        source.add(source.getFirst());
        when(reads.findSigningContracts(eq(ME), any())).thenReturn(source);
        var tasks = run().tasks();
        assertThat(tasks.total()).isEqualTo(25);
        assertThat(tasks.items()).hasSize(20).extracting(Task::key).doesNotHaveDuplicates();
    }

    @Test
    void monthlyTerminationReviewOnlyWhileNoticeIsOpenAndBeforeItsBillingDeadline() {
        when(reads.findTerminationProposals(eq(ME), eq(NOW), any(), any())).thenReturn(List.of(
                new TerminationRow("c1", "HD", "invoice", """
                [{"id":"notice","invoiceId":"invoice","version":1,"status":"NOTICE",
                  "notifiedAt":"2026-11-30T16:00:00Z","respondBy":"2026-11-30T18:00:00Z"}]
                """)));
        assertThat(run().tasks().items()).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo("TERMINATION_RESPONSE");
            assertThat(t.clock()).isEqualTo(Clock.BILLING);
            assertThat(t.href()).contains("invoice=invoice");
        });
        when(reads.findTerminationProposals(eq(ME), eq(NOW), any(), any())).thenReturn(List.of(
                new TerminationRow("c1", "HD", "invoice", """
                [{"id":"notice","invoiceId":"invoice","version":2,"status":"REVIEW_REQUESTED",
                  "notifiedAt":"2026-11-30T16:00:00Z","respondBy":"2026-11-30T18:00:00Z"}]
                """)));
        assertThat(run().tasks().items()).isEmpty();
    }

    @Test
    void sixMonthChartStillReturnsTwelveMonthTotals() {
        var s = TenantDashboardService.spending(List.of(
                new SpendRow(Instant.parse("2026-01-01T00:00:00Z"), PaymentType.MONTHLY_RENT, BigDecimal.TEN),
                new SpendRow(NOW, PaymentType.INITIAL, BigDecimal.ONE)), YearMonth.of(2026, 12), 6, 0);
        assertThat(s.months()).hasSize(6);
        assertThat(s.total()).isEqualByComparingTo("1");
        assertThat(s.total12m()).isEqualByComparingTo("11");
        assertThat(s.total12mExcludingDeposit()).isEqualByComparingTo("10");
    }

    private static ContractAmendment amendment(String id, String status, String proposer, String recipient) {
        return ContractAmendment.builder().id(id).contractId("c1").status(status).proposerId(proposer)
                .recipientId(recipient).respondBy(NOW.plus(Duration.ofDays(4))).build();
    }
}
