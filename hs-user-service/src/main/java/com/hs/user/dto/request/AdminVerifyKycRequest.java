package com.hs.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AdminVerifyKycRequest(
        @NotBlank @Pattern(regexp = "[0-9]{12}", message = "CCCD phải gồm đúng 12 chữ số.") String cccd) {
}
