package com.hs.user.service;

import com.hs.user.dto.response.KycSessionResponse;
import com.hs.user.dto.response.KycStatusResponse;

public interface KycService {

    KycStatusResponse getCurrentStatus(String userId);

    KycSessionResponse createOrReuseSession(String userId);

    void handleDiditWebhook(
            String rawBody,
            String signatureV2,
            String signatureSimple,
            String timestampHeader
    );
}
