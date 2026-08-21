package com.hs.notification.dto.response;

public record OtpVerificationResponse(
        /** Indicates that the supplied OTP matched and the challenge was consumed. */
        boolean verified
) {
}
