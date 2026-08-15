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
        Boolean emailVerified,
        String avatarUrl,
        String phone,
        LocalDate dob,
        Gender gender,
        String role,
        Boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}

