package com.hs.user.dto.response;

import java.time.Instant;

import lombok.Builder;

@Builder
public record UserResponse(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        String avatarUrl,
        String avatarStorageId,
        String phone,
        String roleId,
        String role,
        Boolean onBoarded,
        Boolean active,
        Instant createdAt,
        Instant updatedAt,
        UserAuditActorResponse createdBy,
        UserAuditActorResponse updatedBy
) {
}
