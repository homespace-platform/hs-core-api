package com.hs.user.service;

import com.hs.user.dto.response.KycStatusResponse;

public interface KycService {

    KycStatusResponse getCurrentStatus(String userId);

    /** Cancel PENDING / REVIEW_REQUIRED session so user can start fresh. */
    KycStatusResponse cancelPendingSession(String userId);

}
