package com.hs.user.service.kyc;

import com.hs.common.context.CurrentUser;
import com.hs.common.security.Permissions;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.service.KycService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AdminKycService {
    private final KycService kyc;

    @Transactional(readOnly = true)
    public KycStatusResponse status(String userId) {
        CurrentUser.requireAdminWith(Permissions.KYC_VIEW);
        return kyc.getCurrentStatus(userId);
    }
}
