package com.hs.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpsertPermissionRequest(

        @NotBlank(message = "Permission name must not be blank")
        String name,

        String description
) {
}

