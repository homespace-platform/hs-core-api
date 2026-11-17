package com.hs.user.service.kyc;

import com.hs.user.model.KycVerification;
import com.hs.user.model.constant.*;
import com.hs.user.notification.AccountNotificationWriter;
import com.hs.user.repository.KycVerificationRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KycRetryNoticeSchedulerTest {
    final KycVerificationRepository attempts=mock(KycVerificationRepository.class);
    final AccountNotificationWriter notices=mock(AccountNotificationWriter.class);
    final com.hs.user.repository.UserRepository users=mock(com.hs.user.repository.UserRepository.class);
    final KycRetryNoticeScheduler scheduler=new KycRetryNoticeScheduler(attempts,users,notices,mock(org.springframework.transaction.PlatformTransactionManager.class));
    final Instant now=Instant.parse("2026-11-17T05:00:00Z");
    KycVerification attempt(KycStatus status) {
        var v=KycVerification.builder().id("attempt").userId("user").provider(KycProvider.VNPT).status(status).expiresAt(now).build();
        when(attempts.findAttemptOwner("attempt")).thenReturn(Optional.of("user"));
        var user=new com.hs.user.model.User();user.setId("user");user.setActive(true);
        when(users.findByIdForUpdate("user")).thenReturn(Optional.of(user));
        when(attempts.findFirstByUserIdOrderByCreatedAtDesc("user")).thenReturn(Optional.of(v));
        when(attempts.lockById("attempt")).thenReturn(Optional.of(v));return v;
    }
    @Test void elapsedAttemptGetsOneRetryNoticeWithoutChangingKycStatus() {
        var v=attempt(KycStatus.PENDING);scheduler.notifyLocked("attempt",now);scheduler.notifyLocked("attempt",now.plusSeconds(60));
        verify(notices).enqueueKyc(v,AccountNotificationWriter.KycEvent.KYC_RETRY_REQUIRED);
        assertEquals(KycStatus.PENDING,v.getStatus());assertEquals(now,v.getRetryNoticeSentAt());
    }
    @ParameterizedTest @EnumSource(value=KycStatus.class,names={"VERIFIED","REJECTED","EXPIRED","NOT_VERIFIED"})
    void terminalOrIntentionallyCancelledAttemptIsSilent(KycStatus status) {
        attempt(status);scheduler.notifyLocked("attempt",now);verifyNoInteractions(notices);
    }
    @Test void refreshedSessionOrPriorProviderFailureDoesNotSendAnotherNotice() {
        var v=attempt(KycStatus.PENDING);v.setExpiresAt(now.plusSeconds(1));scheduler.notifyLocked("attempt",now);
        v.setExpiresAt(now);v.setRetryNoticeSentAt(now.minusSeconds(1));scheduler.notifyLocked("attempt",now);
        verifyNoInteractions(notices);
    }
    @Test void supersededAttemptsDoNotSpamHistoricalRetryNotices() {
        attempt(KycStatus.PENDING);
        when(attempts.findFirstByUserIdOrderByCreatedAtDesc("user")).thenReturn(Optional.of(KycVerification.builder().id("new-attempt").build()));
        scheduler.notifyLocked("attempt",now);verifyNoInteractions(notices);verify(attempts,never()).save(any());
    }
}
