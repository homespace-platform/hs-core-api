package com.hs.user.dto.response;

import java.time.Instant;
import java.time.LocalDate;

import com.hs.user.model.constant.Gender;

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
        LocalDate dob,
        Gender gender,
        String roleId,
        String role,
        Boolean onBoarded,
        Boolean active,
        Boolean kycVerified,
        /** True when role is ADMIN — KYC is optional (may still verify). */
        Boolean kycOptional,
        Instant createdAt,
        Instant updatedAt,
        AddressResponse address
) {
}

