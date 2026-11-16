package com.hs.user.repository;

import com.hs.user.model.VnptKycStep;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VnptKycStepRepository extends JpaRepository<VnptKycStep, String> {
    List<VnptKycStep> findByAttemptIdOrderByCreatedAtAsc(String attemptId);
    long countByAttemptId(String attemptId);
    boolean existsByAttemptIdAndUploadHashAndStateAndKind(String attemptId, String uploadHash, String state, String kind);
    long deleteByCreatedAtBefore(java.time.Instant cutoff);
}
