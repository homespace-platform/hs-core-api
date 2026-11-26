package com.hs.listing.membership;

import com.hs.common.time.BillingTime;
import com.hs.user.notification.AccountNotificationWriter;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MembershipExpiryReminderSchedulerTest {
    final MembershipPeriodRepository periods=mock(MembershipPeriodRepository.class);
    final MembershipService service=mock(MembershipService.class);
    final AccountNotificationWriter notices=mock(AccountNotificationWriter.class);
    final BillingTime time=mock(BillingTime.class);
    final MembershipExpiryReminderScheduler scheduler=new MembershipExpiryReminderScheduler(periods,service,notices,
            mock(org.springframework.transaction.PlatformTransactionManager.class),time);
    final Instant now=Instant.parse("2031-11-17T05:00:00Z");
    final Instant soon=now.plus(Duration.ofDays(3));
    final Instant since=now.minus(Duration.ofDays(7));

    MembershipPeriod period(Instant end) {
        var p=new MembershipPeriod();p.setOwnerId("owner");p.setTier(MembershipTier.STARTER);p.setEndAt(end);
        return p;
    }
    @Test void schedulerSelectsDueOwnersOnTheBillingClockAndRereadsUnderTheOwnerLock() {
        when(time.now()).thenReturn(now);
        var p=period(now.plus(Duration.ofDays(2)));
        when(periods.findExpiryNoticeOwnerIds(eq(now),eq(soon),eq(since),org.mockito.ArgumentMatchers.eq(""),any())).thenReturn(List.of("owner"));
        when(periods.findExpiryNotices("owner",now,soon,since)).thenReturn(List.of(p));
        scheduler.remindExpiring();
        var order=inOrder(service,periods,notices);
        order.verify(service).lockOwner("owner");
        order.verify(periods).findExpiryNotices("owner",now,soon,since);
        order.verify(notices).enqueueMembershipExpiry("owner",p.getEndAt(),AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRING_SOON);
        assertEquals(p.getEndAt(),p.getExpiryReminderFor());
        assertNull(p.getExpiredNoticeFor());
        verify(periods).save(p);
    }
    @Test void anExpiredRowIsMarkedSeparatelyWithoutChangingQuotaOrExpiry() {
        var p=period(now);p.setUsed(4);
        when(periods.findExpiryNotices("owner",now,soon,since)).thenReturn(List.of(p));
        scheduler.remindLocked("owner",now);
        verify(notices).enqueueMembershipExpiry("owner",now,AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRED);
        assertEquals(now,p.getExpiredNoticeFor());assertEquals(now,p.getEndAt());assertEquals(4,p.getUsed());
    }
    @Test void nothingDueAfterAConcurrentRenewalSendsNothing() {
        scheduler.remindLocked("owner",now);
        verifyNoInteractions(notices);
        verify(periods,never()).save(any());
    }
    @Test void everyPageRunsOnceAndAFailingOwnerNeitherLoopsNorStarvesLaterOnes() {
        when(time.now()).thenReturn(now);
        var first=java.util.stream.IntStream.range(0,100).mapToObj(i->String.format("a%03d",i)).toList();
        when(periods.findExpiryNoticeOwnerIds(eq(now),eq(soon),eq(since),org.mockito.ArgumentMatchers.eq(""),any())).thenReturn(first);
        when(periods.findExpiryNoticeOwnerIds(eq(now),eq(soon),eq(since),org.mockito.ArgumentMatchers.eq("a099"),any())).thenReturn(List.of("b"));
        doThrow(new IllegalStateException()).when(service).lockOwner("a000");
        scheduler.remindExpiring();
        verify(service).lockOwner("a000");
        verify(service).lockOwner("b");
        verify(periods,times(2)).findExpiryNoticeOwnerIds(any(),any(),any(),any(),any());
    }
    private static Instant eq(Instant value) { return org.mockito.ArgumentMatchers.eq(value); }
}
