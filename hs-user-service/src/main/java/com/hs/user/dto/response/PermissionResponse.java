package com.hs.user.dto.response;

import java.time.Instant;

import lombok.Builder;

@Builder
public record PermissionResponse(
        String id,
        String name,
        String description,
        Boolean active,
        Instant createdAt,
        Instant updatedAt,
        UserAuditActorResponse createdBy,
        UserAuditActorResponse updatedBy
) {
}

