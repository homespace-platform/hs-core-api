package com.hs.listing.membership;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.model.Listing;
import com.hs.listing.repository.ListingStatusHistoryRepository;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MembershipServiceTest {
    private final ListingMembershipRepository repository = mock(ListingMembershipRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ListingStatusHistoryRepository histories = mock(ListingStatusHistoryRepository.class);
    private final MembershipService service = new MembershipService(repository, users, histories);

    @Test
    void upgradeRetainsConsumedUsesAndFirstPurchaseDeadline() {
        ListingMembership m = membership(MembershipTier.STARTER, 2, 0);
        m.setPaidPriceVnd(49_000);
        m.setFirstPaidAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(users.lockMembershipOwner("owner")).thenReturn(Optional.of(new User()));
        when(repository.findById("owner")).thenReturn(Optional.of(m));
        var first = m.getFirstPaidAt();

        var quote = service.quote("owner", MembershipTier.PRO);
        assertEquals(80_000, quote.amountVnd());
        assertEquals(28, quote.remainingUsesAfter());
        assertEquals("UPGRADE", quote.mode());
        assertTrue(service.activate("owner", MembershipTier.PRO, true));
        assertEquals(first, m.getFirstPaidAt());
        assertEquals(2, m.getPaidUsed());
        assertEquals(MembershipTier.PRO, m.getTier());
    }

    @Test
    void ethUpgradeUsesVerifiedMiningTimeAtTwentyFourHourBoundary() {
        Instant firstPaidAt = Instant.now().minus(25, ChronoUnit.HOURS);
        ListingMembership before = membership(MembershipTier.STARTER, 2, 0);
        before.setFirstPaidAt(firstPaidAt);
        before.setPaidPriceVnd(49_000);
        ListingMembership after = membership(MembershipTier.STARTER, 2, 0);
        after.setFirstPaidAt(firstPaidAt);
        after.setPaidPriceVnd(49_000);
        when(users.lockMembershipOwner("owner")).thenReturn(Optional.of(new User()));
        when(repository.findById("owner")).thenReturn(Optional.of(before), Optional.of(after));

        assertTrue(service.activateAt("owner", MembershipTier.PRO, true,
                firstPaidAt.plus(24, ChronoUnit.HOURS).minusSeconds(1)));
        assertEquals(MembershipTier.PRO, before.getTier());
        assertFalse(service.activateAt("owner", MembershipTier.PRO, true,
                firstPaidAt.plus(24, ChronoUnit.HOURS).plusSeconds(1)));
        assertEquals(MembershipTier.STARTER, after.getTier());
    }

    @Test
    void pendingReservationsPreventFreeOveruseAndApprovalConsumesOnce() {
        ListingMembership m = new ListingMembership();
        m.setOwnerId("owner"); m.setFreeUsed(2);
        when(users.lockMembershipOwner("owner")).thenReturn(Optional.of(new User()));
        when(repository.findById("owner")).thenReturn(Optional.of(m));
        Listing first = new Listing(); first.setOwnerId("owner");
        Listing second = new Listing(); second.setOwnerId("owner");

        service.reserve(first, false);
        assertEquals(1, m.getFreeReserved());
        assertThrows(AppException.class, () -> service.reserve(second, false));
        service.finish(first, true);
        assertEquals(3, m.getFreeUsed());
        assertEquals(0, m.getFreeReserved());
        assertNull(first.getPublicationReservation());
    }

    @Test
    void fullPriceRenewalAfterUpgradeWindowResetsCycle() {
        ListingMembership m = membership(MembershipTier.STARTER, 7, 0);
        m.setFirstPaidAt(Instant.now().minus(25, ChronoUnit.HOURS));
        m.setPaidPriceVnd(49_000);
        when(users.lockMembershipOwner("owner")).thenReturn(Optional.of(new User()));
        when(repository.findById("owner")).thenReturn(Optional.of(m));

        var quote = service.quote("owner", MembershipTier.STARTER);
        assertEquals("NEW", quote.mode());
        assertEquals(49_000, quote.amountVnd());
        assertEquals(10, quote.remainingUsesAfter());
        assertTrue(service.activate("owner", MembershipTier.STARTER, false));
        assertEquals(0, m.getPaidUsed());
    }

    @Test
    void adminSubmissionDoesNotSpendOwnersFreeUses() {
        ListingMembership m = new ListingMembership(); m.setOwnerId("owner"); m.setFreeUsed(2);
        when(users.lockMembershipOwner("owner")).thenReturn(Optional.of(new User()));
        when(repository.findById("owner")).thenReturn(Optional.of(m));
        Listing listing = new Listing(); listing.setOwnerId("owner");
        service.reserve(listing, true);
        service.finish(listing, true);
        assertEquals(2, m.getFreeUsed());
        assertEquals(0, m.getFreeReserved());
    }

    private ListingMembership membership(MembershipTier tier, int used, int reserved) {
        ListingMembership m = new ListingMembership();
        m.setOwnerId("owner"); m.setTier(tier); m.setPaidUsed(used); m.setPaidReserved(reserved);
        m.setPaidExpiresAt(Instant.now().plus(5, ChronoUnit.DAYS));
        return m;
    }
}
