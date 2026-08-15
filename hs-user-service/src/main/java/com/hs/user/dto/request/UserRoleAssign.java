package com.hs.user.dto.request;

import jakarta.validation.constraints.NotBlank;


public record UserRoleAssign(
        @NotBlank(message = "ROLE_NOT_EXISTED")
        String roleId,

        @NotBlank(message = "USER_NOT_EXISTED")
        String userId
) {
}

