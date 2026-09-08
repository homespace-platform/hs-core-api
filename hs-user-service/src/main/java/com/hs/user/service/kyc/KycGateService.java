package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.model.User;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Blocks USER actions that require identity verification.
 * ADMIN is trusted via {@link KycPolicy#isIdentitySatisfied}.
 */
@Service
@RequiredArgsConstructor
public class KycGateService {

    private final UserRepository userRepository;
    private final KycVerificationRepository kycVerificationRepository;

    @Transactional(readOnly = true)
    public void requireVerifiedIdentity(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new AppException(UserErrorCode.USER_NOT_EXISTED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));

        boolean diditVerified = kycVerificationRepository.existsByUserIdAndProviderAndStatus(
                userId, KycProvider.DIDIT, KycStatus.VERIFIED);

        if (!KycPolicy.isIdentitySatisfied(user, diditVerified)) {
            throw new AppException(UserErrorCode.KYC_REQUIRED);
        }
    }
}
