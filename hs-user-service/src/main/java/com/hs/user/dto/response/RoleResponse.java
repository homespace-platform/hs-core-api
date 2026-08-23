package com.hs.user.dto.response;

import java.time.Instant;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;

@Builder
public record RoleResponse(
        String id,
        String name,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Set<PermissionResponse> permissions,
        Boolean active,
        Instant createdAt,
        Instant updatedAt,
        UserAuditActorResponse createdBy,
        UserAuditActorResponse updatedBy
) {
}

