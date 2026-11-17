package com.hs.listing.membership;

import com.hs.user.notification.AccountNotificationWriter;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MembershipExpiryReminderSchedulerTest {
    final ListingMembershipRepository memberships=mock(ListingMembershipRepository.class);
    final MembershipService service=mock(MembershipService.class);
    final AccountNotificationWriter notices=mock(AccountNotificationWriter.class);
    final MembershipExpiryReminderScheduler scheduler=new MembershipExpiryReminderScheduler(memberships,service,notices,mock(org.springframework.transaction.PlatformTransactionManager.class));
    final Instant now=Instant.parse("2026-11-17T05:00:00Z");
    ListingMembership membership(Instant expiry) {
        var m=new ListingMembership();m.setOwnerId("owner");m.setTier(MembershipTier.STARTER);m.setPaidExpiresAt(expiry);
        when(memberships.findById("owner")).thenReturn(Optional.of(m));return m;
    }
    @Test void ownerGetsOneSoonAndOneExpiryNoticeForTheActualPaidExpiry() {
        var m=membership(now.plus(Duration.ofDays(2)));Instant expiry=m.getPaidExpiresAt();
        scheduler.remindLocked("owner",now);scheduler.remindLocked("owner",now.plusSeconds(1));
        verify(notices).enqueueMembershipExpiry("owner",expiry,AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRING_SOON);
        scheduler.remindLocked("owner",expiry);scheduler.remindLocked("owner",expiry.plusSeconds(1));
        verify(notices).enqueueMembershipExpiry("owner",expiry,AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRED);
        assertEquals(expiry,m.getPaidExpiresAt());assertEquals(MembershipTier.STARTER,m.getTier());
    }
    @Test void renewedExpiryIsReReadUnderThePurchaseLockAndOldReminderDoesNotSuppressRenewal() {
        var m=membership(now.plus(Duration.ofDays(2)));Instant old=m.getPaidExpiresAt();m.setExpiryReminderFor(old);
        m.setPaidExpiresAt(now.plus(Duration.ofDays(30)));scheduler.remindLocked("owner",now);verifyNoInteractions(notices);
        scheduler.remindLocked("owner",now.plus(Duration.ofDays(28)));
        verify(notices).enqueueMembershipExpiry("owner",m.getPaidExpiresAt(),AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRING_SOON);
        var order=inOrder(service,memberships,notices);order.verify(service).lockOwner("owner");order.verify(memberships).findById("owner");
    }
    @Test void freeAndUnknownMembershipsAreSilentAndExpiredDoesNotSendADelayedSoonNotice() {
        var m=membership(now);m.setTier(MembershipTier.FREE);scheduler.remindLocked("owner",now);verifyNoInteractions(notices);
        m.setTier(MembershipTier.STARTER);scheduler.remindLocked("owner",now);
        verify(notices).enqueueMembershipExpiry("owner",now,AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRED);
        verify(notices,never()).enqueueMembershipExpiry(any(),any(),eq(AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRING_SOON));
    }
}
