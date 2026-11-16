package com.hs.user.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.dto.response.KycVerifiedDetailsResponse;
import com.hs.user.model.KycVerification;
import com.hs.user.model.UserKycDetails;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.service.kyc.KycPolicy;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.UserKycDetailsRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.KycService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KycServiceImpl implements KycService {

    private static final Set<KycStatus> REUSABLE = EnumSet.of(
            KycStatus.PENDING,
            KycStatus.REVIEW_REQUIRED
    );

    private static final String REASON_USER_CANCELLED =
            "Bạn đã hủy phiên xác minh. Nhấn Xác minh lại khi sẵn sàng.";

    private final UserRepository userRepository;
    private final KycVerificationRepository kycVerificationRepository;
    private final UserKycDetailsRepository details;

    @Override
    @Transactional(readOnly = true)
    public KycVerifiedDetailsResponse getVerifiedDetails(String userId) {
        requireUser(userId);
        return kycVerificationRepository.findFirstByUserIdAndStatusOrderByVerifiedAtDesc(userId, KycStatus.VERIFIED)
                .filter(v -> v.getProvider() == KycProvider.VNPT)
                .flatMap(v -> details.findById(userId))
                .map(UserKycDetails::toResponse).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public KycStatusResponse getCurrentStatus(String userId) {
        requireUser(userId);
        Optional<KycVerification> latest =
                kycVerificationRepository.findFirstByUserIdAndStatusOrderByVerifiedAtDesc(userId, KycStatus.VERIFIED)
                        .or(() -> kycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(userId));

        if (latest.isEmpty()) {
            return new KycStatusResponse(
                    KycStatus.NOT_VERIFIED, null, KycProvider.VNPT, null, null, null);
        }

        KycVerification v = latest.get();
        KycStatus status = v.getProvider() == KycProvider.VNPT && REUSABLE.contains(v.getStatus())
                && v.getExpiresAt() != null && !v.getExpiresAt().isAfter(Instant.now()) ? KycStatus.EXPIRED : v.getStatus();
        if (v.getProvider() != KycProvider.VNPT && REUSABLE.contains(status)) status = KycStatus.EXPIRED;
        return new KycStatusResponse(
                status,
                v.getVerifiedAt(),
                v.getProvider(),
                v.getProvider() == KycProvider.VNPT ? v.getId() : v.getProviderSessionId(),
                null,
                v.getRejectionReason()
        );
    }

    @Override
    @Transactional
    public KycStatusResponse cancelPendingSession(String userId) {
        requireUser(userId);

        if (kycVerificationRepository.existsByUserIdAndProviderInAndStatus(userId, KycPolicy.ACCEPTED_PROVIDERS, KycStatus.VERIFIED)) {
            throw new AppException(UserErrorCode.KYC_ALREADY_VERIFIED);
        }

        List<KycVerification> active = kycVerificationRepository
                .findByUserIdAndStatusInOrderByCreatedAtDesc(userId, REUSABLE);

        if (active.isEmpty()) {
            throw new AppException(UserErrorCode.KYC_CANCEL_NOT_ALLOWED);
        }

        for (KycVerification candidate : active) {
            KycVerification verification = kycVerificationRepository.lockById(candidate.getId()).orElseThrow();
            if (verification.getStatus() == KycStatus.VERIFIED) throw new AppException(UserErrorCode.KYC_ALREADY_VERIFIED);
            verification.setStatus(KycStatus.EXPIRED);
            verification.setSessionUrl(null);
            verification.setVerifiedAt(null);
            verification.setRejectionReason(REASON_USER_CANCELLED);
            kycVerificationRepository.save(verification);
        }

        log.info("Cancelled {} pending KYC session(s) for user={}", active.size(), userId);
        return getCurrentStatus(userId);
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank() || !userRepository.existsById(userId)) {
            throw new AppException(UserErrorCode.USER_NOT_EXISTED);
        }
    }

}
