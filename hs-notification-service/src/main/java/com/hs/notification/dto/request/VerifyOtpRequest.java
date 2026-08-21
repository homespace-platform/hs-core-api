package com.hs.notification.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
        /** Six-digit code received by the user or printed in the development log. */
        @NotBlank
        @Pattern(regexp = "^[0-9]{6}$", message = "OTP must contain exactly 6 digits")
        String code
) {
}
