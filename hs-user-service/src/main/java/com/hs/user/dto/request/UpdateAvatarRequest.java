package com.hs.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateAvatarRequest(
        @NotBlank(message = "Storage ID must not be blank")
        String storageId
) {
}

