package com.hs.user.dto.response;

import java.time.Instant;

import lombok.Builder;

@Builder
public record PublicUserProfileResponse(
        String id,
        String username,
        String firstName,
        String lastName,
        String avatarUrl,
        Boolean kycVerified,
        String role,
        Instant createdAt,
        String provinceName
) {
}
