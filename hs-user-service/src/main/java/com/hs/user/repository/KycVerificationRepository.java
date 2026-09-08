package com.hs.user.repository;

import com.hs.user.model.KycVerification;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KycVerificationRepository extends JpaRepository<KycVerification, String> {

    Optional<KycVerification> findFirstByUserIdAndProviderOrderByCreatedAtDesc(
            String userId, KycProvider provider);

    Optional<KycVerification> findByProviderAndProviderSessionId(
            KycProvider provider, String providerSessionId);

    List<KycVerification> findByUserIdAndProviderAndStatusInOrderByCreatedAtDesc(
            String userId, KycProvider provider, Collection<KycStatus> statuses);

    boolean existsByUserIdAndProviderAndStatus(String userId, KycProvider provider, KycStatus status);
}
