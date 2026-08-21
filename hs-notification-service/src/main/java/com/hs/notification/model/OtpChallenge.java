package com.hs.notification.model;

import java.time.Instant;

public record OtpChallenge(
        /** Public identifier supplied by the client when resending or verifying an OTP. */
        String id,

        /** Delivery channel used for this challenge, such as EMAIL or SMS. */
        OtpChannel channel,

        /** Business operation that requires verification, such as LOGIN or REGISTER. */
        OtpPurpose purpose,

        /** Normalized email address or E.164 phone number receiving the OTP. */
        String destination,

        /** HMAC-SHA256 digest of the OTP; the plain code is never persisted. */
        String codeHash,

        /** Number of unsuccessful verification attempts already made. */
        int attempts,

        /** Attempt limit captured when the challenge was created. */
        int maxAttempts,

        /** UTC timestamp at which this challenge was issued. */
        Instant createdAt,

        /** UTC timestamp after which the challenge must no longer be accepted. */
        Instant expiresAt
) {
    public OtpChallenge withAttempts(int value) {
        return new OtpChallenge(id, channel, purpose, destination, codeHash, value,
                maxAttempts, createdAt, expiresAt);
    }
}
