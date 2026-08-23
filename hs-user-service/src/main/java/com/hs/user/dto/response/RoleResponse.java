package com.hs.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.Set;

@Builder
public record RoleResponse(
        String id,
        String name,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Set<PermissionResponse> permissions
) {
}

