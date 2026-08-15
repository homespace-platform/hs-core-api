package com.hs.user.dto.response;

import lombok.Builder;

import java.util.Set;

@Builder
public record RoleResponse(
        String id,
        String name,
        String description,
        Set<PermissionResponse> permissions
) {
}

