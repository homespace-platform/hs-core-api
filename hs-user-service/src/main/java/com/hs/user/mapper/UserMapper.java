package com.hs.user.mapper;

import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.model.User;

public class UserMapper {

    public static UserProfileResponse mapToUserProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .emailVerified(user.getEmailVerified())
                .avatarUrl(user.getAvatarUrl())
                .phone(user.getPhone())
                .dob(user.getDob())
                .gender(user.getGender())
                .role(user.getRole() != null ? user.getRole().getName() : null)
                .active(user.getActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}

