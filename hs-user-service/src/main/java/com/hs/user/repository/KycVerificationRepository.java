package com.hs.user.repository;

import com.hs.user.model.KycVerification;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KycVerificationRepository extends JpaRepository<KycVerification, String> {

    Optional<KycVerification> findFirstByUserIdAndStatusOrderByVerifiedAtDesc(String userId, KycStatus status);
    Optional<KycVerification> findFirstByUserIdOrderByCreatedAtDesc(String userId);
    List<KycVerification> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, Collection<KycStatus> statuses);
    long countByUserIdAndProviderAndCreatedAtAfter(String userId, KycProvider provider, java.time.Instant since);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select k from KycVerification k where k.id = :id")
    Optional<KycVerification> lockById(@org.springframework.data.repository.query.Param("id") String id);

    boolean existsByUserIdAndProviderInAndStatus(String userId, Collection<KycProvider> providers, KycStatus status);

}
