package com.hs.user.repository;

import com.hs.user.model.KycVerification;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KycVerificationRepository extends JpaRepository<KycVerification, String> {

    @org.springframework.data.jpa.repository.Query("select k.id from KycVerification k where k.provider = com.hs.user.model.constant.KycProvider.VNPT "
        + "and k.status in (com.hs.user.model.constant.KycStatus.PENDING,com.hs.user.model.constant.KycStatus.REVIEW_REQUIRED) "
        + "and k.expiresAt <= :now and k.retryNoticeSentAt is null "
        + "and exists (select u.id from User u where u.id = k.userId and u.active = true) "
        + "and not exists (select newer.id from KycVerification newer where newer.userId = k.userId and newer.createdAt > k.createdAt) order by k.id")
    List<String> findExpiredAttemptIdsNeedingNotice(@org.springframework.data.repository.query.Param("now") java.time.Instant now,
            org.springframework.data.domain.Pageable page);
    @org.springframework.data.jpa.repository.Query("select k.userId from KycVerification k where k.id = :id")
    Optional<String> findAttemptOwner(@org.springframework.data.repository.query.Param("id") String id);

    Optional<KycVerification> findFirstByUserIdAndStatusOrderByVerifiedAtDesc(String userId, KycStatus status);
    Optional<KycVerification> findFirstByUserIdOrderByCreatedAtDesc(String userId);
    List<KycVerification> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, Collection<KycStatus> statuses);
    long countByUserIdAndProviderAndCreatedAtAfter(String userId, KycProvider provider, java.time.Instant since);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select k from KycVerification k where k.id = :id")
    Optional<KycVerification> lockById(@org.springframework.data.repository.query.Param("id") String id);

    boolean existsByUserIdAndProviderInAndStatus(String userId, Collection<KycProvider> providers, KycStatus status);

}
