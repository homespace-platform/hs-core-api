package com.hs.listing.membership;

import com.hs.common.time.BillingTime;
import com.hs.user.notification.AccountNotificationWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;

/** Notifications only: quota is derived from membership_periods and never depends on this job. */
@Component @RequiredArgsConstructor @Slf4j
public class MembershipExpiryReminderScheduler {
    private static final Duration SOON=Duration.ofDays(3);
    // Expired notices older than this are dropped so the scan never grows with history.
    private static final Duration LOOKBACK=Duration.ofDays(7);
    private static final int PAGE=100;
    private final MembershipPeriodRepository periods;
    private final MembershipService membershipService;
    private final AccountNotificationWriter notifications;
    private final PlatformTransactionManager transactions;
    private final BillingTime time;

    // Hourly, and once at startup.
    @Scheduled(fixedDelay=3600000)
    public void remindExpiring() {
        Instant now=time.now();
        String after="";
        while(true) { // keyset paging: a failing owner cannot loop forever or starve later ones
            var owners=periods.findExpiryNoticeOwnerIds(now,now.plus(SOON),now.minus(LOOKBACK),after,PageRequest.of(0,PAGE));
            for(String owner:owners) {
                try { new TransactionTemplate(transactions).executeWithoutResult(tx->remindLocked(owner,now)); }
                catch(RuntimeException failure) { log.warn("Membership expiry notification deferred ({})",failure.getClass().getSimpleName()); }
            }
            if(owners.size()<PAGE)return;
            after=owners.get(owners.size()-1);
        }
    }
    void remindLocked(String owner,Instant now) {
        // Same owner lock as purchases and upgrades; re-run the due query after a concurrent renewal.
        membershipService.lockOwner(owner);
        for(MembershipPeriod period:periods.findExpiryNotices(owner,now,now.plus(SOON),now.minus(LOOKBACK))) {
            boolean expired=!period.getEndAt().isAfter(now);
            notifications.enqueueMembershipExpiry(owner,period.getEndAt(),expired
                ?AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRED
                :AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRING_SOON);
            if(expired)period.setExpiredNoticeFor(period.getEndAt());else period.setExpiryReminderFor(period.getEndAt());
            periods.save(period);
        }
    }
}
