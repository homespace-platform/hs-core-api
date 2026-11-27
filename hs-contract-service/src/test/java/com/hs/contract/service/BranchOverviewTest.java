package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.dto.branch.BranchOverviewResponse.*;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.BranchContractAssignmentRepository;
import com.hs.contract.repository.MonthlyInvoiceRepository;
import com.hs.listing.dto.response.BranchOverviewListingRow;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RoomManualStatus;
import com.hs.listing.repository.BranchRoomListingRepository;
import com.hs.listing.repository.BranchSlotRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.payment.model.PaymentLineItem;
import com.hs.payment.repository.PaymentLineItemRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.hs.contract.dto.branch.BranchOverviewResponse.PaymentStatus.*;
import static com.hs.contract.dto.branch.BranchOverviewResponse.RoomStatus.*;
import static com.hs.contract.service.BranchOverviewService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchOverviewTest {
    private static final Instant NOW = Instant.parse("2026-11-30T17:00:00Z"); // Dec 1 in Vietnam
    private static final LocalDate TODAY = LocalDate.of(2026, 12, 1);
    private static final YearMonth DEC = YearMonth.of(2026, 12);

    @ParameterizedTest
    @CsvSource(value = {
            "UNPAID,CONFIRMED,-1,-,PAID", "PAID,-,-1,-,PAID",
            "OVERDUE,TRANSFER_REPORTED,-1,-,PENDING_CONFIRMATION",
            "UNPAID,AWAITING_TRANSFER,-1,-,OVERDUE", "OVERDUE,OVERDUE,0,-,OVERDUE",
            "UNPAID,REJECTED,0,-,DUE_SOON", "UNPAID,AWAITING_TRANSFER,259200,-,DUE_SOON",
            "UNPAID,AWAITING_TRANSFER,259201,-,NOT_DUE", "OVERDUE,OVERDUE,0,PAYMENT_EXTENSION,DUE_SOON",
            "OVERDUE,OVERDUE,400000,PAYMENT_EXTENSION,DUE_SOON", "OVERDUE,OVERDUE,-1,PAYMENT_EXTENSION,OVERDUE",
            "UNPAID,-,0,-,-", "UNPAID,CANCELLED,0,-,-", "UNPAID,EXPIRED,0,-,-",
            "DRAFT,CONFIRMED,0,-,-", "CANCELLED,CONFIRMED,0,-,-", "ROLLED_OVER,CONFIRMED,0,-,-"
    }, nullValues = "-")
    void monthlyColors(MonthlyInvoiceStatus invoice, com.hs.payment.model.constant.PaymentStatus transfer,
                       long secondsToDue, String resolution, PaymentStatus expected) {
        assertEquals(expected, invoiceStatus(invoice, NOW.plusSeconds(secondsToDue), resolution, transfer, NOW));
        assertNull(invoiceStatus(invoice, null, resolution, transfer, NOW));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "AWAITING_TRANSFER,0,OVERDUE", "REJECTED,-1,OVERDUE", "TRANSFER_REPORTED,-1,PENDING_CONFIRMATION",
            "CONFIRMED,-1,PAID", "OVERDUE,259200,DUE_SOON", "AWAITING_TRANSFER,259201,NOT_DUE",
            "CANCELLED,0,-", "EXPIRED,0,-"
    }, nullValues = "-")
    void firstRentColors(com.hs.payment.model.constant.PaymentStatus transfer, long secondsToDue, PaymentStatus expected) {
        assertEquals(expected, initialStatus(transfer, NOW.plusSeconds(secondsToDue), NOW));
    }

    @Test
    void firstRentRequiresRentAndChecksOnlyRentPlusFeesAgainstReceiptTotal() {
        var rent = line("RENT", 100);
        var fee = line("SERVICE_FEE", 20);
        var deposit = line("DEPOSIT", 300);
        assertTrue(validFirstRent(BigDecimal.valueOf(120), List.of(rent, fee, deposit)));
        assertFalse(validFirstRent(BigDecimal.valueOf(119), List.of(rent, fee)));
        assertFalse(validFirstRent(BigDecimal.valueOf(300), List.of(line("RENT", 0), deposit)));
        assertFalse(validFirstRent(BigDecimal.valueOf(300), List.of(deposit)));
        assertFalse(validFirstRent(null, List.of(rent)));
    }

    @Test
    void monthUsesVietnamMidnightAndCarriesOnlyOpenOverdueDebt() {
        var beforeMidnight = NOW.minusNanos(1);
        assertTrue(inMonth(item("dec", PAID, NOW, null, Clock.BILLING), DEC, NOW, false));
        for (var status : List.of(PAID, UNISSUED, DUE_SOON)) {
            assertFalse(inMonth(item("nov", status, beforeMidnight, null, Clock.BILLING), DEC, NOW.minusSeconds(1), false));
        }
        assertFalse(inMonth(item("nov-paid", PAID, beforeMidnight, null, Clock.BILLING), DEC, NOW, false));
        assertFalse(inMonth(item("nov-draft", UNISSUED, beforeMidnight, null, Clock.BILLING), DEC, NOW, false));
        assertTrue(inMonth(item("nov-late", OVERDUE, beforeMidnight, null, Clock.BILLING), DEC, NOW, false));
        var pending = item("nov-pending", PENDING_CONFIRMATION, beforeMidnight, NOW.plusSeconds(90), Clock.BILLING);
        assertTrue(inMonth(pending, DEC, NOW, false));
        assertFalse(inMonth(pending, DEC, beforeMidnight, false));
        assertTrue(inMonth(pending, DEC, beforeMidnight, true));
        assertFalse(inMonth(item("jan", OVERDUE, Instant.parse("2026-12-31T17:00:00Z"), null, Clock.BILLING), DEC, NOW, false));
    }

    @Test
    void priorityUsesConfirmationDeadlineAndEachItemsOwnClockThenId() {
        Instant real = NOW.plusSeconds(1000);
        var order = paymentOrder(NOW, real);
        var items = Stream.of(item("paid", PAID, NOW, null, Clock.BILLING),
                item("soon", DUE_SOON, NOW, null, Clock.BILLING), item("blue", NOT_DUE, NOW, null, Clock.BILLING),
                item("draft", UNISSUED, NOW, null, Clock.BILLING), item("late", OVERDUE, NOW, null, Clock.BILLING),
                item("missing", PENDING_CONFIRMATION, NOW, null, Clock.BILLING),
                item("b", PENDING_CONFIRMATION, NOW.minusSeconds(90), NOW.plusSeconds(60), Clock.BILLING),
                item("a", PENDING_CONFIRMATION, NOW, NOW.plusSeconds(60), Clock.BILLING),
                item("real", PENDING_CONFIRMATION, real, real.plusSeconds(59), Clock.REAL)).sorted(order).toList();
        assertThat(items).extracting(Payment::id)
                .containsExactly("late", "real", "a", "b", "missing", "soon", "blue", "draft", "paid");
        var layer = room("room", RoomManualStatus.NONE, List.of(), List.of(), List.of(), List.of(), List.of(), items, TODAY).payments();
        assertEquals(new PaymentLayer(OVERDUE, items.size(), items), layer);
    }

    @Test
    void roomPrecedenceAndNextStayKeepTheirOwnTargets() {
        var tenant = stay("tenant", TODAY.minusMonths(1));
        var hold = stay("hold", TODAY);
        var later = stay("later", TODAY.plusDays(2));
        var sooner = stay("sooner", TODAY.plusDays(1));
        var live = List.of("listing-z", "listing-a");
        var occupied = room("r", RoomManualStatus.REPAIR, List.of(tenant), List.of(later, hold), List.of(sooner), live, List.of(), List.of(), TODAY);
        assertEquals(OCCUPIED, occupied.status());
        assertEquals(tenant.target(), occupied.target());
        assertEquals(new Stay(sooner.tenantName(), sooner.moveInDate(), sooner.target()), occupied.nextStay());
        assertEquals(new PaymentLayer(NONE, 0, List.of()), occupied.payments());
        assertEquals(RESERVED, room("r", RoomManualStatus.REPAIR, List.of(), List.of(hold), List.of(sooner), live, List.of(), List.of(), TODAY).status());
        assertEquals(MOVING_IN, room("r", RoomManualStatus.EXTERNAL_RENT, List.of(), List.of(later), List.of(), live, List.of(), List.of(), TODAY).status());
        for (var flag : List.of(RoomManualStatus.REPAIR, RoomManualStatus.EXTERNAL_RENT)) {
            assertEquals(RoomStatus.valueOf(flag.name()), room("r", flag, List.of(), List.of(), List.of(), live, List.of(), List.of(), TODAY).status());
        }
        var advertised = room("r", RoomManualStatus.NONE, List.of(), List.of(), List.of(), live, List.of(), List.of(), TODAY);
        assertEquals(ADVERTISED, advertised.status());
        assertEquals("/dashboard/properties/view?id=listing-a", advertised.target().href());
        var unlinked = room("r", RoomManualStatus.NONE, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), TODAY);
        assertEquals(UNLINKED, unlinked.status());
        assertNull(unlinked.target());
        assertNull(unlinked.listingStatus());
    }

    @Test
    void vacantNeedsALinkedListingAndPointsAtTheLowestIdWithItsOperationalStatus() {
        var hidden = new BranchOverviewListingRow("b", ListingStatus.HIDDEN, null, null);
        var revision = new BranchOverviewListingRow("a", ListingStatus.PENDING_REVIEW, ListingStatus.EXPIRED, null);
        var vacant = room("r", RoomManualStatus.NONE, List.of(), List.of(), List.of(), List.of(), List.of(hidden, revision), List.of(), TODAY);
        assertEquals(VACANT, vacant.status());
        assertEquals("/dashboard/properties/view?id=a", vacant.target().href());
        assertEquals("EXPIRED", vacant.listingStatus());
        var single = room("r", RoomManualStatus.NONE, List.of(), List.of(), List.of(), List.of(), List.of(hidden), List.of(), TODAY);
        assertEquals("HIDDEN", single.listingStatus());
        // A manual flag still beats the linked listing, and nothing but VACANT carries listingStatus.
        var repair = room("r", RoomManualStatus.REPAIR, List.of(), List.of(), List.of(), List.of(), List.of(hidden), List.of(), TODAY);
        assertEquals(REPAIR, repair.status());
        assertNull(repair.listingStatus());
    }

    @Test
    void liveListingUnderReviewExpiresOnRealTime() {
        var revision = new BranchOverviewListingRow("l", ListingStatus.PENDING_REVIEW, ListingStatus.PUBLISHED, NOW);
        assertTrue(revision.publiclyListed(NOW.minusNanos(1)));
        assertFalse(revision.publiclyListed(NOW));
        for (var status : List.of(ListingStatus.PENDING_REVIEW, ListingStatus.RESERVED, ListingStatus.RENTED)) {
            assertFalse(new BranchOverviewListingRow("l", status, null, null).publiclyListed(NOW));
        }
    }

    @Test
    void unilateralHandoverFreesRoomAndRestoresConflictingLeaseWithoutLosingHistoricalLink() {
        var oldStart = TODAY.minusMonths(1);
        var next = new BranchLeaseRow("next", "next", ContractStatus.ACTIVE, null, null, null, TODAY, 3, "Next", null);
        var stored = Map.of("old", "r", "next", "r");
        var old = new BranchLeaseRow("old", "old", ContractStatus.TERMINATED, TODAY, null, null,
                oldStart, 1, "Old", null, "i", "[{\"invoiceId\":\"i\",\"status\":\"ENDED\",\"handoverAt\":null}]");
        var returned = new BranchLeaseRow("old", "old", ContractStatus.TERMINATED, TODAY, null, null,
                oldStart, 1, "Old", null, "i", "[{\"invoiceId\":\"i\",\"status\":\"ENDED\",\"handoverAt\":\"2026-11-30T17:00:00Z\"}]");
        assertFalse(BranchRoomSchedule.historical(old));
        assertTrue(BranchRoomSchedule.living(old, TODAY));
        assertEquals(Map.of("old", "r"), BranchRoomSchedule.shownRooms(List.of(next, old), stored, TODAY));
        assertTrue(BranchRoomSchedule.historical(returned));
        assertFalse(BranchRoomSchedule.living(returned, TODAY));
        assertEquals(stored, BranchRoomSchedule.shownRooms(List.of(next, returned), stored, TODAY));
    }

    @Test
    void projectedPeriodsStayAnchoredAndEndAtEffectiveCutoff() {
        var start = LocalDate.of(2026, 1, 31);
        var cutoff = LocalDate.of(2026, 3, 5);
        var periods = BranchBillingPeriods.of(start, 3, cutoff);
        assertEquals(List.of(new BranchBillingPeriods.Period(0, start, LocalDate.of(2026, 2, 28)),
                new BranchBillingPeriods.Period(1, LocalDate.of(2026, 2, 28), cutoff)), periods);
        assertEquals(Instant.parse("2026-03-04T16:59:59Z"), MonthlyBillingSchedule.dueAt(periods.getFirst().endExclusive()));
    }

    @Test
    void ownerGuardStopsBeforeReadingAnySourcesAndDefaultMonthUsesBillingTime() {
        var branches = mock(PropertyBranchRepository.class);
        var slots = mock(BranchSlotRepository.class);
        var listings = mock(BranchRoomListingRepository.class);
        var assignments = mock(BranchContractAssignmentRepository.class);
        var invoices = mock(MonthlyInvoiceRepository.class);
        var payments = mock(PaymentRequestRepository.class);
        var lines = mock(PaymentLineItemRepository.class);
        var time = mock(BillingTime.class);
        var service = new BranchOverviewService(branches, slots, listings, assignments, invoices, payments, lines, time);
        assertEquals(404, assertThrows(AppException.class, () -> service.overview("branch", "other", DEC)).getStatusCode().value());
        verifyNoInteractions(slots, listings, assignments, invoices, payments, lines, time);

        when(branches.findByIdAndOwnerIdAndActiveTrue("branch", "owner")).thenReturn(Optional.of(new PropertyBranch()));
        when(time.now()).thenReturn(NOW);
        var before = Instant.now();
        var overview = service.overview("branch", "owner", null);
        assertEquals("2026-12", overview.month());
        assertEquals(NOW, overview.billingNow());
        assertThat(overview.realNow()).isBetween(before, Instant.now());
        assertTrue(overview.rooms().isEmpty());
        assertTrue(overview.unplacedPayments().isEmpty());
        verify(time).now();
    }

    private static StayRow stay(String id, LocalDate moveIn) {
        return new StayRow(id, moveIn, id, new Target(TargetKind.CONTRACT, id, "/dashboard/landlord/contracts/" + id + "?tab=overview"));
    }

    private static PaymentLineItem line(String type, long amount) {
        return PaymentLineItem.builder().type(type).amount(BigDecimal.valueOf(amount)).build();
    }

    private static Payment item(String id, PaymentStatus status, Instant due, Instant confirmation, Clock clock) {
        return new Payment(id, PaymentKind.MONTHLY, "c", status, due, confirmation, clock, null);
    }
}
