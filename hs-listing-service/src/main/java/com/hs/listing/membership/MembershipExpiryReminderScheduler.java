package com.hs.listing.membership;

import com.hs.user.notification.AccountNotificationWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.Objects;

@Component @RequiredArgsConstructor @Slf4j
public class MembershipExpiryReminderScheduler {
    private final ListingMembershipRepository memberships;
    private final MembershipService membershipService;
    private final AccountNotificationWriter notifications;
    private final PlatformTransactionManager transactions;

    @Scheduled(fixedDelay=60000)
    public void remindExpiring() {
        Instant now=Instant.now();
        for(String owner:memberships.findExpiryNoticeOwnerIds(now,now.plus(Duration.ofDays(3)),PageRequest.of(0,100))) {
            try { new TransactionTemplate(transactions).executeWithoutResult(tx->remindLocked(owner,now)); }
            catch(RuntimeException failure) { log.warn("Membership expiry notification deferred ({})",failure.getClass().getSimpleName()); }
        }
    }
    void remindLocked(String owner,Instant now) {
        // Same owner lock as purchases and upgrades; re-read the current expiry after a concurrent renewal.
        membershipService.lockOwner(owner);
        memberships.findById(owner).filter(m->m.getTier()!=null && m.getTier()!=MembershipTier.FREE
                && m.getPaidExpiresAt()!=null && !m.getPaidExpiresAt().isAfter(now.plus(Duration.ofDays(3)))).ifPresent(m->{
            boolean expired=!m.getPaidExpiresAt().isAfter(now);
            if(Objects.equals(m.getPaidExpiresAt(),expired?m.getExpiredNoticeFor():m.getExpiryReminderFor()))return;
            notifications.enqueueMembershipExpiry(owner,m.getPaidExpiresAt(),expired
                ?AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRED
                :AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRING_SOON);
            if(expired)m.setExpiredNoticeFor(m.getPaidExpiresAt());else m.setExpiryReminderFor(m.getPaidExpiresAt());
            memberships.save(m);
        });
    }
}
