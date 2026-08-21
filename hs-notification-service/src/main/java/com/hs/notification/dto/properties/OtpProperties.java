package com.hs.notification.dto.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.otp")
public record OtpProperties(
        /** Number of numeric digits generated for each OTP code. */
        int length,

        /** Maximum lifetime of an OTP challenge before Redis expires it. */
        Duration ttl,

        /** Minimum waiting time before another OTP can be sent to the same destination. */
        Duration resendCooldown,

        /** Maximum number of failed verification attempts allowed for one challenge. */
        int maxAttempts,

        /** Maximum number of OTP messages that one destination may request per hour. */
        int maxSendsPerHour,

        /** Secret key used to create the HMAC-SHA256 digest stored instead of the plain OTP. */
        String hashSecret
) {
}
