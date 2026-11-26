package com.hs.listing.membership;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.Listing;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.hs.listing.membership.MembershipEvent.Type.*;
import static com.hs.listing.membership.MembershipTier.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MembershipServiceTest {
    // Years away from the real clock, so any comparison that mixes clocks fails here.
    private static final Instant CREATED = Instant.parse("2031-01-01T00:00:00Z");
    private static final Duration MONTH = Duration.ofDays(30);

    private final MembershipPeriodRepository periods = mock(MembershipPeriodRepository.class);
    private final MembershipEventRepository events = mock(MembershipEventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final BillingTime time = mock(BillingTime.class);
    private final MembershipService service = new MembershipService(periods, events, users, time);
    private final List<MembershipPeriod> rows = new ArrayList<>();
    private final List<MembershipEvent> ledger = new ArrayList<>();
    private Instant now = CREATED;

    /** In-memory stand-in for the queries; the JPQL itself is parsed in MembershipMappingTest. */
    @BeforeEach
    void repositories() {
        User owner = new User();
        owner.setId("owner");
        owner.setCreatedAt(CREATED);
        when(users.findById("owner")).thenReturn(Optional.of(owner));
        when(users.lockMembershipOwner("owner")).thenReturn(Optional.of(owner));
        when(time.now()).thenAnswer(call -> now);
        when(periods.save(any())).thenAnswer(call -> {
            MembershipPeriod p = call.getArgument(0);
            if (p.getId() == null) {
                p.setId("p" + (rows.size() + 1));
                rows.add(p);
            }
            return p;
        });
        when(periods.findById(any())).thenAnswer(call -> rows.stream()
                .filter(p -> p.getId().equals(call.getArgument(0))).findFirst());
        when(periods.findByOwnerIdAndFreeStartAt(any(), any())).thenAnswer(call -> rows.stream()
                .filter(p -> call.<Instant>getArgument(1).equals(p.getFreeStartAt())).findFirst());
        when(periods.findActivePaid(any(), any())).thenAnswer(call -> active(call.getArgument(1))
                .filter(p -> p.getKind() == MembershipKind.PLAN && p.getTier() != FREE)
                .sorted(Comparator.comparing(MembershipPeriod::getStartAt).reversed()).toList());
        when(periods.findActiveAddons(any(), any())).thenAnswer(call -> active(call.getArgument(1))
                .filter(p -> p.getKind() == MembershipKind.ADDON)
                .sorted(Comparator.comparing(MembershipPeriod::getEndAt)).toList());
        when(events.save(any())).thenAnswer(call -> {
            ledger.add(call.getArgument(0));
            return call.getArgument(0);
        });
    }

    private java.util.stream.Stream<MembershipPeriod> active(Instant at) {
        return rows.stream().filter(p -> !p.getStartAt().isAfter(at) && p.getEndAt().isAfter(at));
    }

    @Test
    void freeRefillsExactlyEveryThirtyDaysFromAccountCreationAfterAnyNumberOfSkippedPeriods() {
        consume(3);
        now = CREATED.plus(MONTH).minusNanos(1000);
        assertEquals(0, service.view("owner").remainingUses());
        assertEquals(CREATED.plus(MONTH), service.view("owner").quotaResetsAt());
        assertError(ListingErrorCode.LISTING_QUOTA_EXHAUSTED, () -> service.reserve(listing(), false));
        now = CREATED.plus(MONTH);
        var refilled = service.view("owner");
        assertEquals(3, refilled.remainingUses());
        assertEquals(0, refilled.freeUsed());
        assertEquals(CREATED.plus(MONTH.multipliedBy(2)), refilled.quotaResetsAt());
        assertNull(refilled.expiresAt());
        now = CREATED.plus(MONTH.multipliedBy(14)).plusSeconds(5);
        assertEquals(3, service.view("owner").remainingUses()); // No rollover.
        service.reserve(listing(), false);
        assertEquals(2, rows.size());
        assertEquals(CREATED.plus(MONTH.multipliedBy(14)), rows.get(1).getStartAt());
        assertEquals(CREATED.plus(MONTH.multipliedBy(15)), rows.get(1).getEndAt());
    }

    @Test
    void timeBeforeAccountCreationClampsToTheFirstPeriod() {
        now = CREATED.minus(Duration.ofDays(400));
        assertEquals(CREATED.plus(MONTH), service.view("owner").quotaResetsAt());
        service.reserve(listing(), false);
        service.reserve(listing(), false);
        assertEquals(1, rows.size());
        assertEquals(CREATED, rows.getFirst().getStartAt());
        assertEquals(1, service.view("owner").remainingUses());
    }

    @Test
    void viewWritesNothingAndOnlyTheFirstReservationCreatesOneFreeRow() {
        var view = service.view("owner");
        assertEquals("FREE", view.tier());
        assertEquals(3, view.remainingUses());
        assertNull(view.addonExpiresAt());
        verify(periods, never()).save(any());
        verify(events, never()).save(any());
        Listing first = listing(), second = listing();
        service.reserve(first, false);
        service.reserve(first, false); // Idempotent resubmit.
        service.reserve(second, false);
        assertEquals(1, rows.size());
        assertEquals(2, rows.getFirst().getReserved());
        assertEquals("FREE", first.getPublicationReservation());
        assertEquals(rows.getFirst().getId(), second.getPublicationPeriodId());
        assertEquals(2, ledger.size());
    }

    @Test
    void approveAndRejectAfterRolloverSettleTheRowHoldingTheReservation() {
        Listing approved = listing(), rejected = listing();
        service.reserve(approved, false);
        service.reserve(rejected, false);
        now = CREATED.plus(MONTH.multipliedBy(3));
        service.finish(approved, true);
        service.finish(rejected, false);
        MembershipPeriod old = rows.getFirst();
        assertEquals(1, old.getUsed());
        assertEquals(0, old.getReserved());
        assertEquals(3, service.view("owner").remainingUses());
        assertNull(approved.getPublicationReservation());
        assertNull(approved.getPublicationPeriodId());
        assertEquals(List.of(RESERVE, RESERVE, CONSUME, RELEASE), ledger.stream().map(MembershipEvent::getType).toList());
        assertEquals(List.of(-1, -1, 0, 1), ledger.stream().map(MembershipEvent::getDelta).toList());
        assertTrue(ledger.stream().allMatch(e -> e.getPeriodId().equals(old.getId()) && e.getListingId() != null));
        assertError(ListingErrorCode.MEMBERSHIP_RESERVATION_REQUIRED, () -> service.finish(approved, true));
    }

    @Test
    void approveAndRejectAfterReplacementNeverTouchTheNewPlan() {
        assertTrue(service.activate("owner", STARTER, false, "o1"));
        Listing approved = listing(), rejected = listing();
        service.reserve(approved, false);
        service.reserve(rejected, false);
        assertEquals("PAID", approved.getPublicationReservation());
        now = CREATED.plus(Duration.ofDays(2));
        assertTrue(service.activate("owner", PRO, false, "o2"));
        MembershipPeriod old = rows.get(0), next = rows.get(1);
        assertEquals(now, old.getEndAt()); // Closed, so one paid row is active.
        service.finish(approved, true);
        service.finish(rejected, false);
        assertEquals(1, old.getUsed());
        assertEquals(0, old.getReserved());
        assertEquals(30, next.remaining());
        assertEquals(30, service.view("owner").remainingUses());
    }

    @Test
    void upgradeEditsTheActiveRowAndKeepsUsageAndStart() {
        assertTrue(service.activate("owner", STARTER, false, "o1"));
        consume(2);
        service.reserve(listing(), false);
        Instant start = rows.getFirst().getStartAt();
        now = start.plus(Duration.ofHours(23));
        var quote = service.quote("owner", PRO);
        assertEquals("UPGRADE", quote.mode());
        assertEquals(80_000, quote.amountVnd());
        assertEquals(27, quote.remainingUsesAfter());
        assertEquals(start.plus(Duration.ofHours(24)), quote.upgradeDeadline());
        assertTrue(service.activate("owner", PRO, true, "o2"));
        assertEquals(1, rows.size());
        MembershipPeriod row = rows.getFirst();
        assertEquals(PRO, row.getTier());
        assertEquals(30, row.getQuota());
        assertEquals(129_000, row.getPriceVnd());
        assertEquals(2, row.getUsed());
        assertEquals(1, row.getReserved());
        assertEquals(start, row.getStartAt());
        assertEquals(now.plus(MONTH), row.getEndAt());
        MembershipEvent upgrade = ledger.getLast();
        assertEquals(UPGRADE, upgrade.getType());
        assertEquals(20, upgrade.getDelta());
        assertEquals(STARTER, upgrade.getOldTier());
        assertEquals(PRO, upgrade.getNewTier());
        assertEquals("o2", upgrade.getOrderId());
        assertEquals(now, upgrade.getOccurredAt());
        assertEquals(PURCHASE, ledger.getFirst().getType());
        assertEquals(10, ledger.getFirst().getDelta());
        assertEquals("o1", ledger.getFirst().getOrderId());
        // The window still counts from the first purchase.
        now = start.plus(Duration.ofHours(24));
        assertEquals("NEW", service.quote("owner", BUSINESS).mode());
        // An upgrade quoted inside the window and paid just after it is still granted.
        assertTrue(service.activate("owner", BUSINESS, true, "o3"));
        assertEquals(BUSINESS, row.getTier());
    }

    @Test
    void aPaidOrderIsGrantedEvenWhenAUseWasReleasedWhileItWasBeingPaid() {
        assertTrue(service.activate("owner", STARTER, false, "o1"));
        consume(9);
        Listing pending = listing();
        service.reserve(pending, false);
        assertEquals("NEW", service.quote("owner", STARTER).mode());
        service.finish(pending, false); // Rejected during payment: one use is back.
        assertTrue(service.activate("owner", STARTER, false, "o2"));
        assertEquals(10, service.view("owner").remainingUses());
        assertTrue(Duration.between(Instant.now().plus(Duration.ofHours(1)),
                service.realTimeOf(now.plus(Duration.ofHours(1)))).abs().toSeconds() < 5);
    }

    @Test
    void aNewListingGetsItsIdIntoTheLedgerBeforeCommit() {
        Listing fresh = listing();
        String id = fresh.getId();
        fresh.setId(null);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.reserve(fresh, false);
            assertTrue(ledger.isEmpty());
            fresh.setId(id); // Assigned when the listing is saved.
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.beforeCommit(false));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        assertEquals(id, ledger.getLast().getListingId());
    }

    @Test
    void buyingFromFreeCountsSpentAndPendingFreeUsesAndRefundsTheNewPlan() {
        consume(1);
        Listing pending = listing();
        service.reserve(pending, false);
        assertEquals(98, service.quote("owner", BUSINESS).remainingUsesAfter());
        assertTrue(service.activate("owner", BUSINESS, false, "o1"));
        var view = service.view("owner");
        assertEquals(98, view.remainingUses());
        assertEquals(1, view.reservedUses());
        service.finish(pending, false); // Rejected: the use comes back to the plan that took it over.
        assertEquals(99, service.view("owner").remainingUses());
        MembershipPeriod free = rows.stream().filter(p -> p.getTier() == FREE).findFirst().orElseThrow();
        assertEquals(0, free.getReserved());
        assertEquals(0, free.getCarriedReserved());
        assertEquals(1, free.getUsed());
    }

    @Test
    void aPaidUpgradeWithoutAnActiveLowerPlanIsRefusedAndNothingDowngrades() {
        assertFalse(service.activate("owner", PRO, true, "o1"));
        assertTrue(service.activate("owner", PRO, false, "o2"));
        assertFalse(service.activate("owner", PRO, true, "o3"));
        assertFalse(service.activate("owner", STARTER, false, "o4"));
        assertEquals(1, rows.size());
    }

    @Test
    void sameTierIsPurchasableOnlyWithNothingRemainingEvenInsideTheUpgradeWindow() {
        assertTrue(service.activate("owner", STARTER, false, "o1"));
        consume(9);
        assertError(ListingErrorCode.MEMBERSHIP_SAME_TIER_ACTIVE, () -> service.quote("owner", STARTER));
        Listing last = listing();
        service.reserve(last, false); // Reserved uses count as spent.
        var quote = service.quote("owner", STARTER);
        assertEquals("NEW", quote.mode());
        assertFalse(quote.upgrade());
        assertEquals(49_000, quote.amountVnd());
        assertEquals(10, quote.remainingUsesAfter());
        // The same frozen instant: both rows share start_at but only the new one is active.
        assertTrue(service.activate("owner", STARTER, false, "o2"));
        assertEquals(10, service.view("owner").remainingUses());
        assertNull(rows.get(1).getFreeStartAt());
        service.finish(last, false);
        assertEquals(1, rows.get(0).remaining());
        assertError(ListingErrorCode.MEMBERSHIP_DOWNGRADE_NOT_ALLOWED, () -> {
            service.activate("owner", PRO, true, "o3");
            service.quote("owner", STARTER);
        });
        assertError(ListingErrorCode.MEMBERSHIP_INVALID_TIER, () -> service.quote("owner", FREE));
    }

    @Test
    void exhaustedPaidPlanCannotUseFreeAndExpiryRestoresTheCurrentFreeBalance() {
        consume(1);
        assertTrue(service.activate("owner", STARTER, false, "o1"));
        consume(9); // The FREE use already spent counts against the plan.
        assertError(ListingErrorCode.LISTING_QUOTA_EXHAUSTED, () -> service.reserve(listing(), false));
        var paid = service.view("owner");
        assertEquals("STARTER", paid.tier());
        assertEquals(0, paid.remainingUses());
        assertEquals(1, paid.freeUsed());
        assertNull(paid.quotaResetsAt());
        assertEquals(CREATED.plus(MONTH), paid.expiresAt());
        Instant end = CREATED.plus(Duration.ofDays(5)).plus(MONTH);
        now = CREATED.plus(Duration.ofDays(5));
        assertTrue(service.activate("owner", PRO, false, "o2"));
        now = end.minusNanos(1000);
        assertEquals("PRO", service.view("owner").tier());
        now = end;
        var free = service.view("owner");
        assertEquals("FREE", free.tier());
        assertEquals(3, free.remainingUses()); // Second FREE period by now.
        now = CREATED.plus(MONTH).minusSeconds(1);
        rows.get(1).setEndAt(now);
        rows.get(2).setEndAt(now);
        assertEquals(2, service.view("owner").remainingUses()); // Still the first period: one use spent.
    }

    @Test
    void addonsAreSpentAfterThePlanByEarliestExpiryAndNeverChangeThePlan() {
        assertTrue(service.activateAddon("owner", 1, "a1"));
        now = CREATED.plus(Duration.ofDays(1));
        assertTrue(service.activateAddon("owner", 2, "a2"));
        assertEquals(PURCHASE, ledger.getLast().getType());
        assertEquals(2, ledger.getLast().getDelta());
        assertEquals(16_000, rows.get(1).getPriceVnd());
        assertNull(rows.get(1).getTier());
        var view = service.view("owner");
        assertEquals("FREE", view.tier());
        assertEquals(3, view.remainingUses());
        assertEquals(3, view.addonRemaining());
        assertEquals(CREATED.plus(Duration.ofDays(90)), view.addonExpiresAt());
        consume(3); // FREE first.
        assertEquals(3, service.view("owner").addonRemaining());
        Listing earliest = listing(), later = listing();
        service.reserve(earliest, false);
        service.reserve(later, false);
        assertEquals("ADDON", earliest.getPublicationReservation());
        assertEquals(rows.get(0).getId(), earliest.getPublicationPeriodId());
        assertEquals(rows.get(1).getId(), later.getPublicationPeriodId());
        var spending = service.view("owner");
        assertEquals(1, spending.addonRemaining());
        assertEquals(2, spending.addonReserved());
        assertEquals(CREATED.plus(Duration.ofDays(91)), spending.addonExpiresAt());
        service.finish(earliest, false);
        assertEquals(1, rows.get(0).remaining());

        // A paid purchase and upgrade leave add-on rows and plan pricing untouched.
        Instant firstEnd = rows.get(0).getEndAt();
        assertTrue(service.activate("owner", STARTER, false, "o1"));
        assertEquals(80_000, service.quote("owner", PRO).amountVnd());
        assertTrue(service.activate("owner", PRO, true, "o2"));
        assertEquals(firstEnd, rows.get(0).getEndAt());
        assertEquals(2, service.view("owner").addonRemaining());
        Listing planFirst = listing();
        service.reserve(planFirst, false);
        assertEquals("PAID", planFirst.getPublicationReservation());
    }

    @Test
    void exhaustedPaidPlanUsesAddonsAndAnAddonDoesNotUnlockOrBlockTheSameTierRule() {
        assertTrue(service.activate("owner", STARTER, false, "o1"));
        assertTrue(service.activateAddon("owner", 1, "a1"));
        assertError(ListingErrorCode.MEMBERSHIP_SAME_TIER_ACTIVE, () -> service.quote("owner", STARTER));
        consume(10);
        assertEquals("NEW", service.quote("owner", STARTER).mode()); // Add-on balance is ignored.
        Listing extra = listing();
        service.reserve(extra, false);
        assertEquals("ADDON", extra.getPublicationReservation());
        assertTrue(rows.stream().noneMatch(p -> p.getTier() == FREE));
        assertError(ListingErrorCode.LISTING_QUOTA_EXHAUSTED, () -> service.reserve(listing(), false));
        assertNull(service.view("owner").addonExpiresAt());
    }

    @Test
    void addonsExpireAtExactlyNinetyDaysAndALateReleaseStaysOnTheExpiredRow() {
        assertTrue(service.activateAddon("owner", 2, "a1"));
        consume(3);
        Listing held = listing();
        service.reserve(held, false);
        now = CREATED.plus(Duration.ofDays(90)).minusNanos(1000);
        assertEquals(1, service.view("owner").addonRemaining());
        now = CREATED.plus(Duration.ofDays(90));
        assertEquals(0, service.view("owner").addonRemaining());
        assertEquals(0, service.view("owner").addonReserved());
        service.finish(held, false);
        assertEquals(2, rows.getFirst().remaining());
        assertEquals(0, service.view("owner").addonRemaining());
    }

    @Test
    void addonQuantityIsLimitedAndPricedOnTheServer() {
        assertEquals(8_000, service.addonAmountVnd(1));
        assertEquals(80_000, service.addonAmountVnd(10));
        for (int quantity : new int[]{0, -1, 11}) {
            assertError(ListingErrorCode.MEMBERSHIP_ADDON_QUANTITY_INVALID, () -> service.addonAmountVnd(quantity));
            assertFalse(service.activateAddon("owner", quantity, "bad"));
        }
        assertTrue(rows.isEmpty());
        assertEquals(new MembershipService.AddonOffer(8_000, 90, 10), service.addonOffer());
        assertTrue(service.plans().stream().allMatch(p -> p.durationDays() == 30));
    }

    @Test
    void adminActorIsExemptButAmbientAdminAuthorityIsNot() {
        Listing moderated = listing();
        service.reserve(moderated, true);
        assertEquals("ADMIN", moderated.getPublicationReservation());
        assertNull(moderated.getPublicationPeriodId());
        service.finish(moderated, true);
        verify(periods, never()).save(any());
        verify(events, never()).save(any());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("owner", "n/a",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ADMIN"))));
        try {
            Listing own = listing();
            service.reserve(own, false);
            assertEquals("FREE", own.getPublicationReservation());
            assertFalse(service.view("owner").unlimited());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private void consume(int count) {
        for (int i = 0; i < count; i++) {
            Listing listing = listing();
            service.reserve(listing, false);
            service.finish(listing, true);
        }
    }

    private static Listing listing() {
        Listing listing = new Listing();
        listing.setId(UUID.randomUUID().toString());
        listing.setOwnerId("owner");
        return listing;
    }

    private static void assertError(ListingErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code.getCode(), assertThrows(AppException.class, action).getCode());
    }
}
