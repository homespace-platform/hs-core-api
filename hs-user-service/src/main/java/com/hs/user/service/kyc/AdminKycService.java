package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.user.constant.RoleConstants;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.service.KycService;
import com.hs.user.utils.CurrentUserUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AdminKycService {
    private final CurrentUserUtils actor;
    private final KycService kyc;

    @Transactional(readOnly = true)
    public KycStatusResponse status(String userId) {
        requireAdmin();
        return kyc.getCurrentStatus(userId);
    }

    private void requireAdmin() {
        var admin = actor.getCurrentUser();
        if (admin.getRole() == null || !RoleConstants.ADMIN.equals(admin.getRole().getName())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }
}
