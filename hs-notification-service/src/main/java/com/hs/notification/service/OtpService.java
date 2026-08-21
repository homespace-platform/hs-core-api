package com.hs.notification.service;

import com.hs.notification.dto.request.CreateOtpChallengeRequest;
import com.hs.notification.dto.response.OtpChallengeResponse;
import com.hs.notification.dto.response.OtpVerificationResponse;

public interface OtpService {
    OtpChallengeResponse createChallenge(CreateOtpChallengeRequest request);

    OtpChallengeResponse resend(String challengeId);

    OtpVerificationResponse verify(String challengeId, String code);
}
