package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.model.KycVerification;
import com.hs.user.model.constant.*;
import com.hs.user.repository.*;
import com.hs.user.service.KycService;
import com.hs.user.service.impl.CccdClaimService;
import com.hs.user.utils.CurrentUserUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class AdminKycService {
    private final UserRepository users;
    private final KycVerificationRepository records;
    private final CccdClaimService claims;
    private final CurrentUserUtils actor;
    private final KycService kyc;

    @Transactional(readOnly = true)
    public KycStatusResponse status(String userId) {
        requireAdmin();
        return kyc.getCurrentStatus(userId);
    }

    @Transactional
    public KycStatusResponse verify(String userId, String cccd) {
        String adminId = requireAdmin();
        if (cccd == null || !cccd.matches("[0-9]{12}")) throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
        claims.lockVerification(userId);
        var user = users.findByIdForUpdate(userId).orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));
        if (!Boolean.TRUE.equals(user.getActive())) throw new AppException(UserErrorCode.USER_DISABLED);
        if (records.existsByUserIdAndProviderInAndStatus(userId, KycPolicy.ACCEPTED_PROVIDERS, KycStatus.VERIFIED))
            throw new AppException(UserErrorCode.KYC_ALREADY_VERIFIED);
        if (!claims.claimInCurrentTransaction(userId, cccd)) throw new AppException(UserErrorCode.KYC_CCCD_ALREADY_USED);
        for (var pending : records.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, List.of(KycStatus.PENDING, KycStatus.REVIEW_REQUIRED))) {
            var locked = records.lockById(pending.getId()).orElseThrow(() -> new AppException(UserErrorCode.KYC_ATTEMPT_INVALID));
            locked.setStatus(KycStatus.EXPIRED);
            locked.setSessionUrl(null);
            locked.setRejectionReason("Phiên đã đóng do ADMIN hoàn tất xác minh danh tính.");
            records.save(locked);
        }
        String id = UUID.randomUUID().toString();
        Instant verifiedAt = Instant.now();
        var verified = KycVerification.builder().id(id).userId(userId).provider(KycProvider.ADMIN)
                .providerSessionId(id).providerStatus("ADMIN_VERIFIED").status(KycStatus.VERIFIED).verifiedAt(verifiedAt).build();
        verified.setCreatedBy(adminId);
        records.save(verified);
        return new KycStatusResponse(KycStatus.VERIFIED, verifiedAt, KycProvider.ADMIN, id, null, null);
    }

    private String requireAdmin() {
        var admin = actor.getCurrentUser();
        if (!KycPolicy.isAdminTrusted(admin)) throw new AppException(ErrorCode.UNAUTHORIZED);
        return admin.getId();
    }
}
