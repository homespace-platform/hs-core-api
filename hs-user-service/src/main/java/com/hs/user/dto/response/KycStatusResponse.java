package com.hs.user.dto.response;

import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import java.time.Instant;

public record KycStatusResponse(
        KycStatus status,
        Instant verifiedAt,
        KycProvider provider,
        String sessionId,
        String sessionUrl,
        String rejectionReason
) {}
