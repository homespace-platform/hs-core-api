package com.hs.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateAvatarRequest(
        @NotBlank(message = "Avatar URL must not be blank")
        @Size(max = 2048, message = "Avatar URL must not exceed 2048 characters")
        @Pattern(regexp = "^https?://.+$", message = "Avatar URL must be a valid HTTP or HTTPS URL")
        String avatarUrl
) {
}

