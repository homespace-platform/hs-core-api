package com.hs.contract.service;

import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchAttentionResponse.*;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.dto.branch.BranchOverviewResponse.ExpiryStatus;
import com.hs.contract.dto.branch.BranchOverviewResponse.RoomStatus;
import com.hs.contract.dto.branch.BranchOverviewResponse.Target;
import com.hs.contract.dto.branch.BranchOverviewResponse.TargetKind;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.LandlordDashboardReadRepository;
import com.hs.contract.repository.LandlordDashboardDetailsRepository;
import com.hs.contract.repository.LandlordDashboardReadRepository.Visit;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordDashboardServiceTest {
    private static final String OWNER = "owner";
    /** 2026-12-01 00:00 in Asia/Ho_Chi_Minh. */
    private static final Instant NOW = Instant.parse("2026-11-30T17:00:00Z");
    private static final Instant REAL = Instant.parse("2026-11-20T12:00:00Z");

    private final BranchAttentionService attention = mock();
    private final LandlordDashboardReadRepository reads = mock();
    private final MembershipService membership = mock();
    private final BillingTime time = mock();
    private final LandlordDashboardDetailsRepository details = mock();
    private final RentalOnboardingService onboarding = mock();
    private final LandlordDashboardService service =
            new LandlordDashboardService(attention, reads, membership, time, details, onboarding);

    @BeforeEach
    void clock() {
        when(time.now()).thenReturn(NOW);
        when(attention.overview(OWNER)).thenReturn(new BranchAttentionService.Overview(NOW,REAL,Map.of(),Map.of(),List.of()));
        when(details.money(any(),any(),any(),any(),any())).thenReturn(new LandlordDashboardDetailsRepository.Money(
                BigDecimal.ZERO,0,BigDecimal.ZERO,BigDecimal.ZERO,0));
        when(details.extraWork(any(),any(),any(),any())).thenReturn(new LandlordDashboardDetailsRepository.ExtraWork(Map.of(),0,List.of()));
        when(details.orders(any(),any(),any(),any())).thenReturn(new LandlordDashboardDetailsRepository.Orders(0,null,0,BigDecimal.ZERO));
    }

    private static BigDecimal vnd(long amount) {
        return BigDecimal.valueOf(amount);
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    private static Work work(String key, WorkType type, Priority priority) {
        return new Work(key, type, null, "P1", "label", null, null, Clock.BILLING, priority,
                new Target(TargetKind.CONTRACT, "c", "/dashboard/x"));
    }

    @Test
    void monthIsBoundedInVietnamTimeAndDepositsAreNeverRevenue() {
        // November 2026 in UTC+7: [2026-10-31T17:00Z, 2026-11-30T17:00Z).
        when(reads.receiptTotals(eq(OWNER), any(), any())).thenReturn(rows(
                row(PaymentType.INITIAL, PaymentDirection.TENANT_TO_LANDLORD, 1L, vnd(13_000_000)),
                row(PaymentType.MONTHLY_RENT, PaymentDirection.TENANT_TO_LANDLORD, 2L, vnd(7_000_000)),
                row(PaymentType.RESERVATION_DEPOSIT, PaymentDirection.TENANT_TO_LANDLORD, 1L, vnd(2_000_000)),
                row(PaymentType.DEPOSIT_REFUND, PaymentDirection.LANDLORD_TO_TENANT, 1L, vnd(9_000_000)),
                row(PaymentType.DEPOSIT_ADJUSTMENT, PaymentDirection.TENANT_TO_LANDLORD, 1L, vnd(500_000)),
                row(PaymentType.INITIAL_PAYMENT_REFUND, PaymentDirection.LANDLORD_TO_TENANT, 1L, vnd(8_000_000))));
        when(reads.receiptDepositLines(eq(OWNER), any(), any(), any())).thenReturn(rows(
                row(PaymentType.INITIAL, PaymentDirection.TENANT_TO_LANDLORD, vnd(10_000_000)),
                row(PaymentType.INITIAL_PAYMENT_REFUND, PaymentDirection.LANDLORD_TO_TENANT, vnd(5_000_000))));
        when(reads.expectedTotals(eq(OWNER), any(), any(), any())).thenReturn(rows(
                row(PaymentType.INITIAL, vnd(13_000_000)), row(PaymentType.MONTHLY_RENT, vnd(5_000_000))));
        when(reads.expectedDepositLines(eq(OWNER), any(), any(), any(), any()))
                .thenReturn(rows(row(PaymentType.INITIAL, vnd(10_000_000))));
        when(attention.overview(OWNER)).thenReturn(new BranchAttentionService.Overview(NOW,REAL,
                Map.of("b",List.of(work("PAYMENT_OVERDUE:a",WorkType.PAYMENT_OVERDUE,Priority.URGENT),
                        work("PAYMENT_OVERDUE:b",WorkType.PAYMENT_OVERDUE,Priority.URGENT),
                        work("PAYMENT_OVERDUE:c",WorkType.PAYMENT_OVERDUE,Priority.URGENT))),Map.of(),List.of()));
        when(details.overdueAmount(eq(OWNER),any())).thenReturn(vnd(3_600_000));
        Instant due = Instant.parse("2026-12-02T00:00:00Z");
        when(reads.waitingOnOwner(OWNER)).thenReturn(rows(
                row(PaymentDirection.TENANT_TO_LANDLORD, PaymentStatus.TRANSFER_REPORTED, 4L, vnd(14_000_000),
                        due, null),
                row(PaymentDirection.TENANT_TO_LANDLORD, PaymentStatus.REJECTED, 1L, vnd(3_500_000), null, due),
                row(PaymentDirection.LANDLORD_TO_TENANT, PaymentStatus.AWAITING_TRANSFER, 1L, vnd(7_000_000), null,
                        due.plusSeconds(60)),
                row(PaymentDirection.LANDLORD_TO_TENANT, PaymentStatus.OVERDUE, 1L, vnd(1_000_000), null, due)));

        var result = service.summary(OWNER, YearMonth.of(2026, 11), Set.of("money"));

        var from = ArgumentCaptor.forClass(Instant.class);
        var to = ArgumentCaptor.forClass(Instant.class);
        verify(reads).receiptTotals(eq(OWNER), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-10-31T17:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-11-30T17:00:00Z"));
        var revenue = result.revenue();
        // rent 3M of INITIAL + 7M monthly; the reservation deposit, deposit refund and adjustment are not revenue.
        assertThat(revenue.receivedVnd()).isEqualByComparingTo("7000000");
        assertThat(revenue.receiptCount()).isEqualTo(3);
        assertThat(revenue.refundedVnd()).isEqualByComparingTo("3000000");
        assertThat(revenue.depositReceivedVnd()).isEqualByComparingTo("12000000");
        assertThat(revenue.expectedVnd()).isEqualByComparingTo("8000000");
        assertThat(revenue.overdueCount()).isEqualTo(3);
        assertThat(revenue.overdueVnd()).isEqualByComparingTo("3600000");
        assertThat(revenue.awaitingConfirm().count()).isEqualTo(4);
        assertThat(revenue.awaitingConfirm().nextDueAt()).isEqualTo(due);
        assertThat(revenue.rejected().vnd()).isEqualByComparingTo("3500000");
        assertThat(revenue.refundsOwed().count()).isEqualTo(2);
        assertThat(revenue.refundsOwed().vnd()).isEqualByComparingTo("8000000");
        assertThat(revenue.refundsOwed().nextDueAt()).isEqualTo(due);
        assertThat(result.month()).isEqualTo("2026-11");
        assertThat(result.errors()).containsExactly(Map.entry("money", false));
    }

    @Test
    void emptyMonthIsZeroAndDefaultMonthFollowsBillingClock() {
        var result = service.summary(OWNER, null, Set.of("money"));

        assertThat(result.month()).isEqualTo("2026-12");
        assertThat(result.revenue().receivedVnd()).isEqualByComparingTo("0");
        assertThat(result.revenue().refundedVnd()).isEqualByComparingTo("0");
        assertThat(result.revenue().overdueCount()).isZero();
        assertThat(result.revenue().awaitingConfirm().nextDueAt()).isNull();
        verify(details,never()).overdueAmount(any(),any());
    }

    @Test
    void onlyAskedGroupsRunAndOneFailureLeavesTheOthers() {
        when(reads.receivingAccounts(OWNER)).thenThrow(new IllegalStateException("db"));
        when(membership.view(OWNER)).thenReturn(new MembershipService.View("PRO", 7, 1, NOW, 0, false, null,
                2, 0, null));

        var result = service.summary(OWNER, null, new LinkedHashSet<>(List.of("setup", "membership")));

        assertThat(result.setup()).isNull();
        assertThat(result.membership().remainingUses()).isEqualTo(7);
        assertThat(result.membership().addonRemaining()).isEqualTo(2);
        assertThat(result.errors()).containsExactly(Map.entry("setup", true), Map.entry("membership", false));
        assertThat(result.revenue()).isNull();
        assertThat(result.todo()).isNull();
        verifyNoInteractions(attention);
        verify(reads, never()).receiptTotals(any(), any(), any());
    }

    @Test
    void failedAttentionScanFailsOnlyTheGroupsThatNeedIt() {
        when(attention.overview(OWNER)).thenThrow(new IllegalStateException("scan"));

        var result = service.summary(OWNER, null, new LinkedHashSet<>(List.of("todo", "rooms", "setup")));

        assertThat(result.todo()).isNull();
        assertThat(result.rooms()).isNull();
        assertThat(result.listings()).isNotNull();
        assertThat(result.setup()).isNotNull();
        assertThat(result.errors()).containsExactly(Map.entry("todo", true), Map.entry("rooms", true),
                Map.entry("setup", false));
        verify(attention, times(1)).overview(OWNER);
    }

    @Test
    void todoCountsEveryItemGroupsThemAndAddsUnbranchedRequests() {
        var urgent = work("PAYMENT_OVERDUE:a", WorkType.PAYMENT_OVERDUE, Priority.URGENT);
        var soon = work("OWNER_SIGN:b", WorkType.OWNER_SIGN, Priority.SOON);
        var other = work("LEASE_EXPIRING:c", WorkType.LEASE_EXPIRING, Priority.NORMAL);
        var meter = work("METER_TODAY:d", WorkType.METER_TODAY, Priority.URGENT);
        var overview = new BranchAttentionService.Overview(NOW, REAL,
                Map.of("b1", List.of(other, urgent), "b2", List.of(meter, soon)), new EnumMap<>(RoomStatus.class),
                List.of());
        when(attention.overview(OWNER)).thenReturn(overview);
        when(reads.unbranchedRentalRequests(OWNER, NOW)).thenReturn(2L);
        when(reads.unbranchedAppointments(eq(OWNER), any(), any())).thenReturn(3L);

        var todo = service.summary(OWNER, null, Set.of("todo")).todo();

        assertThat(todo.total()).isEqualTo(9);
        assertThat(todo.urgent()).isEqualTo(2);
        assertThat(todo.items()).extracting(i -> i.key()).containsExactly("METER_TODAY:d", "PAYMENT_OVERDUE:a",
                "OWNER_SIGN:b", "LEASE_EXPIRING:c", "UNBRANCHED_RENTAL", "UNBRANCHED_VIEWING");
        assertThat(todo.items().getFirst().href()).isEqualTo("/dashboard/x");
        assertThat(todo.groups()).containsEntry("OVERDUE", 1).containsEntry("METER", 1).containsEntry("SIGN", 1)
                .containsEntry("OTHER", 1).containsEntry("UNBRANCHED_RENTAL", 2)
                .containsEntry("UNBRANCHED_VIEWING", 3).containsEntry("RENTAL_REQUEST", 0);
    }

    @Test
    void todoListIsBoundedToTen() {
        List<Work> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) many.add(work(String.format("OWNER_SIGN:%02d", i), WorkType.OWNER_SIGN, Priority.SOON));
        when(attention.overview(OWNER)).thenReturn(new BranchAttentionService.Overview(NOW, REAL,
                Map.of("b", many), new EnumMap<>(RoomStatus.class), List.of()));

        var todo = service.summary(OWNER, null, Set.of("todo")).todo();

        assertThat(todo.total()).isEqualTo(25);
        assertThat(todo.items()).hasSize(10);
    }

    @Test
    void roomsSplitStatusesAndExpiringLeasesAreCapped() {
        var by = new EnumMap<RoomStatus, Integer>(RoomStatus.class);
        by.put(RoomStatus.OCCUPIED, 18);
        by.put(RoomStatus.VACANT, 3);
        by.put(RoomStatus.UNLINKED, 1);
        by.put(RoomStatus.ADVERTISED, 1);
        by.put(RoomStatus.RESERVED, 1);
        by.put(RoomStatus.MOVING_IN, 1);
        by.put(RoomStatus.REPAIR, 1);
        List<BranchAttentionService.ExpiringLease> leases = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            leases.add(new BranchAttentionService.ExpiringLease("c" + i, "HD-" + i, "P" + i, "Tenant",
                    LocalDate.of(2027, 1, 2), 30, ExpiryStatus.WITHIN_30, "/x"));
        }
        when(attention.overview(OWNER)).thenReturn(new BranchAttentionService.Overview(NOW, REAL, Map.of(), by, leases));

        var result = service.summary(OWNER, null, Set.of("rooms", "contracts"));

        assertThat(result.rooms()).extracting("total", "occupied", "vacant", "reserved", "other")
                .containsExactly(26, 18, 5, 2, 1);
        assertThat(result.expiringContracts()).hasSize(10);
        verify(attention, times(1)).overview(OWNER);
    }

    @Test
    void publishedListingsExcludeExpiredAndPendingReviewUsesReviewStatus() {
        when(reads.listingCounts(OWNER)).thenReturn(rows(
                row(ListingStatus.PUBLISHED, null, 6L, null),
                row(ListingStatus.PENDING_REVIEW, null, 2L, Instant.parse("2026-11-18T00:00:00Z")),
                row(ListingStatus.PENDING_REVIEW, ListingStatus.PUBLISHED, 1L, Instant.parse("2026-11-10T00:00:00Z")),
                row(ListingStatus.DRAFT, null, 4L, null),
                row(ListingStatus.EXPIRED, null, 3L, null)));
        when(reads.pastExpiryListingCounts(eq(OWNER), any())).thenReturn(rows(
                row(ListingStatus.PUBLISHED, null, 2L), row(ListingStatus.EXPIRED, null, 3L)));

        var listings = service.summary(OWNER, null, Set.of("rooms")).listings();

        assertThat(listings.published()).isEqualTo(5);
        assertThat(listings.pendingReview()).isEqualTo(3);
        assertThat(listings.draft()).isEqualTo(4);
        assertThat(listings.oldestPendingSince()).isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
    }

    @Test
    void listingsAreExpiringWithinSevenDaysOfTheRealClock() {
        var captor = ArgumentCaptor.forClass(Instant.class);
        when(reads.expiringListings(eq(OWNER), any(), captor.capture(), any())).thenReturn(List.of(
                new LandlordDashboardReadRepository.ExpiringListing("l1", "Phong A",
                        Instant.parse("2026-11-24T05:00:00Z"), null)));

        var result = service.summary(OWNER, null, Set.of("rooms")).expiringListings();

        assertThat(Duration7(captor.getValue())).isTrue();
        assertThat(result).singleElement().satisfies(l -> {
            assertThat(l.id()).isEqualTo("l1");
            assertThat(l.viewCount()).isZero();
            assertThat(l.href()).isEqualTo("/dashboard/properties/view?id=l1");
        });
    }

    /** The cut-off is realNow + 7 days (owner decision D8), a few seconds after the call at most. */
    private static boolean Duration7(Instant until) {
        long seconds = java.time.Duration.between(Instant.now(), until).getSeconds();
        return seconds > 7 * 86400 - 60 && seconds <= 7 * 86400;
    }

    @Test
    void visitsUseTheProposedTimeSkipThePastAndStopAtFive() {
        LocalDate today = LocalDate.now(BillingTime.ZONE);
        List<Visit> found = new ArrayList<>();
        found.add(new Visit("past", today, LocalTime.MIN, AppointmentStatus.CONFIRMED, false, null, null, "A", "T"));
        found.add(new Visit("moved", today.plusDays(1), LocalTime.of(9, 0), AppointmentStatus.CONFIRMED, true,
                today.plusDays(9), LocalTime.of(8, 0), "B", "T"));
        for (int i = 0; i < 6; i++) {
            found.add(new Visit("v" + i, today.plusDays(2 + i), LocalTime.of(10, 0), AppointmentStatus.PENDING, false,
                    null, null, "C", "T"));
        }
        when(reads.upcomingVisits(eq(OWNER), any(), any(), any())).thenReturn(found);

        var visits = service.summary(OWNER, null, Set.of("leads")).appointments();

        assertThat(visits).extracting(v -> v.id()).containsExactly("v0", "v1", "v2", "v3", "v4");
        assertThat(visits.getFirst().at()).isEqualTo(LocalDateTime.of(today.plusDays(2), LocalTime.of(10, 0))
                .atZone(BillingTime.ZONE).toInstant());
        assertThat(visits.getFirst().href()).isEqualTo("/dashboard/viewing-schedules?appointmentId=v0");
        // "past" started at midnight of today unless the test runs in the first instant of the day.
        assertThat(visits).extracting(v -> v.id()).doesNotContain("past");
    }

    @Test
    void contractsAreCountedByStageAndOthersAreIgnored() {
        when(reads.contractCounts(OWNER)).thenReturn(rows(
                row(ContractStatus.ACTIVE, 18L), row(ContractStatus.TENANT_SIGNATURE_PENDING, 1L),
                row(ContractStatus.LANDLORD_SIGNATURE_PENDING, 1L), row(ContractStatus.PENDING_REVIEW, 2L),
                row(ContractStatus.DRAFT, 1L), row(ContractStatus.CLOSED, 40L)));

        var by = service.summary(OWNER, null, Set.of("contracts")).contracts().byStatus();

        assertThat(by).containsEntry("ACTIVE", 18).containsEntry("SIGNING", 2).containsEntry("DRAFT", 3)
                .hasSize(3);
    }

    @Test
    void setupAloneDoesNotScanBranchesOrQueryAnyOtherGroup() {
        when(reads.receivingAccounts(OWNER)).thenReturn(1L);
        var result=service.summary(OWNER,null,Set.of("setup"));
        assertThat(result.setup().hasReceivingAccount()).isTrue();
        verify(reads).receivingAccounts(OWNER);
        verifyNoMoreInteractions(reads);
        verifyNoInteractions(attention,details,membership,onboarding);
    }

    @Test
    void additionalWorkIsMergedIntoCountsGroupsAndTheBoundedPriorityOrder() {
        var existing=work("OWNER_SIGN:x",WorkType.OWNER_SIGN,Priority.SOON);
        when(attention.overview(OWNER)).thenReturn(new BranchAttentionService.Overview(NOW,REAL,
                Map.of("b",List.of(existing)),Map.of(),List.of()));
        var extra=new com.hs.contract.dto.dashboard.LandlordDashboardResponse.TodoItem("COMPLAINT_RESPOND:x",
                "COMPLAINT_RESPOND",null,"Complaint",NOW.minusSeconds(1),Clock.BILLING,Priority.URGENT,"/complaint");
        when(details.extraWork(eq(OWNER),eq(NOW),any(),any())).thenReturn(
                new LandlordDashboardDetailsRepository.ExtraWork(Map.of("COMPLAINT_RESPOND",1,"CLOSURE_ACTION",12),1,List.of(extra)));
        var todo=service.summary(OWNER,null,Set.of("todo")).todo();
        assertThat(todo.total()).isEqualTo(14);
        assertThat(todo.urgent()).isEqualTo(1);
        assertThat(todo.groups()).containsEntry("COMPLAINT",1).containsEntry("CLOSURE",12).containsEntry("SIGN",1);
        assertThat(todo.items().getFirst()).isEqualTo(extra);
    }

    @Test
    void holdWaitingPartyComesFromTheExistingReadOnlyOnboardingView() {
        when(details.holds(OWNER,NOW)).thenReturn(List.of(
                new LandlordDashboardDetailsRepository.Held("r","Listing","Tenant",NOW.plusSeconds(1))));
        var view=mock(RentalOnboardingService.View.class);
        when(view.nextAction()).thenReturn(new RentalOnboardingService.NextAction("LANDLORD",
                RentalOnboardingService.NextActionCode.CONFIRM_FIRST_RENT,NOW));
        when(onboarding.get("r",OWNER)).thenReturn(view);
        var holds=service.summary(OWNER,null,Set.of("leads")).holds();
        assertThat(holds).singleElement().satisfies(h->assertThat(h.waitingFor()).isEqualTo("LANDLORD"));
        verify(onboarding).get("r",OWNER);
        verifyNoMoreInteractions(onboarding);
    }
}
