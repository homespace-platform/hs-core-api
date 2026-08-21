package com.hs.notification.dto.response;

import java.time.Instant;

public record OtpChallengeResponse(
        /** Identifier used by subsequent resend and verify requests. */
        String challengeId,

        /** Privacy-safe recipient value suitable for display in the UI. */
        String maskedDestination,

        /** UTC timestamp at which the OTP becomes invalid. */
        Instant expiresAt,

        /** Remaining cooldown the client should wait before requesting another OTP. */
        long resendAfterSeconds
) {
}
