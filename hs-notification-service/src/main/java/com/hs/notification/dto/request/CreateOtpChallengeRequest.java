package com.hs.notification.dto.request;

import com.hs.notification.model.OtpChannel;
import com.hs.notification.model.OtpPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOtpChallengeRequest(
        /** Communication channel through which the OTP should be delivered. */
        @NotNull OtpChannel channel,

        /** Business reason for requesting verification. */
        @NotNull OtpPurpose purpose,

        /** Recipient email address or Vietnamese phone number. */
        @NotBlank @Size(max = 320) String destination
) {
}
