package com.hs.user.dto.response;

import java.time.Instant;

import lombok.Builder;

@Builder
public record UserProfileResponse(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        String avatarUrl,
        String avatarStorageId,
        String phone,
        String cccd,
        String roleId,
        String role,
        Boolean onBoarded,
        Boolean active,
        Boolean kycVerified,
        /** Retained for response compatibility; always false for every role. */
        Boolean kycOptional,
        Instant createdAt,
        Instant updatedAt,
        AddressResponse address,
        Integer onboardingVersion,
        Instant onboardingCompletedAt
) {
}

