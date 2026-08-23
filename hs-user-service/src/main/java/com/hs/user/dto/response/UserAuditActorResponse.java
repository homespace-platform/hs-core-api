package com.hs.user.dto.response;

import lombok.Builder;

@Builder
public record UserAuditActorResponse(
        String id,
        String fullName,
        String username,
        String phone,
        String email
) {
}
