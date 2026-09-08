package com.hs.user.dto.response;

import com.hs.user.model.constant.KycStatus;

public record KycSessionResponse(
        String sessionId,
        String url,
        KycStatus status
) {}
