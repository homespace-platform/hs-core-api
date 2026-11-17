package com.hs.user.service.kyc;

import com.hs.user.model.constant.*;
import com.hs.user.notification.AccountNotificationWriter;
import com.hs.user.repository.KycVerificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;

@Component @RequiredArgsConstructor @Slf4j
public class KycRetryNoticeScheduler {
    private final KycVerificationRepository attempts;
    private final com.hs.user.repository.UserRepository users;
    private final AccountNotificationWriter notifications;
    private final PlatformTransactionManager transactions;

    @Scheduled(fixedDelay=60000)
    public void notifyExpiredAttempts() {
        Instant now=Instant.now();
        for(String id:attempts.findExpiredAttemptIdsNeedingNotice(now,PageRequest.of(0,100))) {
            try { new TransactionTemplate(transactions).executeWithoutResult(tx->notifyLocked(id,now)); }
            catch(RuntimeException failure) { log.warn("KYC retry notification deferred ({})",failure.getClass().getSimpleName()); }
        }
    }
    void notifyLocked(String id,Instant now) {
        var owner=attempts.findAttemptOwner(id).orElse(null);
        if(owner==null || users.findByIdForUpdate(owner).filter(u->Boolean.TRUE.equals(u.getActive())).isEmpty())return;
        attempts.lockById(id).filter(v->v.getProvider()==KycProvider.VNPT
            && (v.getStatus()==KycStatus.PENDING || v.getStatus()==KycStatus.REVIEW_REQUIRED)
            && v.getExpiresAt()!=null && !v.getExpiresAt().isAfter(now) && v.getRetryNoticeSentAt()==null).ifPresent(v->{
            if(attempts.findFirstByUserIdOrderByCreatedAtDesc(owner).filter(latest->id.equals(latest.getId())).isEmpty())return;
            notifications.enqueueKyc(v,AccountNotificationWriter.KycEvent.KYC_RETRY_REQUIRED);
            v.setRetryNoticeSentAt(now);attempts.save(v);
        });
    }
}
