package com.hs.user.dto.response;

import java.time.Instant;
import java.time.LocalDate;

import com.hs.user.model.constant.Gender;

import lombok.Builder;

@Builder
public record UserResponse(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        String avatarUrl,
        String phone,
        LocalDate dob,
        Gender gender,
        String roleId,
        String role,
        Boolean onBoarded,
        Boolean active,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
