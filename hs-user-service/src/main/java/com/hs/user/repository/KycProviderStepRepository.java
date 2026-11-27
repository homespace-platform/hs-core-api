package com.hs.user.repository;

import com.hs.user.model.KycProviderStep;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KycProviderStepRepository extends JpaRepository<KycProviderStep, String> {
    List<KycProviderStep> findByProviderAndAttemptIdOrderByCreatedAtAsc(com.hs.user.model.constant.KycProvider provider, String attemptId);
    long countByProviderAndAttemptId(com.hs.user.model.constant.KycProvider provider, String attemptId);
    boolean existsByProviderAndAttemptIdAndUploadHashAndStateAndKind(com.hs.user.model.constant.KycProvider provider, String attemptId, String uploadHash, String state, String kind);
    long deleteByProviderAndCreatedAtBefore(com.hs.user.model.constant.KycProvider provider, java.time.Instant cutoff);
}
