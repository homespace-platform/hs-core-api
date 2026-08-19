package com.hs.user.mapper;

import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.dto.response.UserResponse;
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
                .roleId(user.getRole() != null ? user.getRole().getId() : null)
                .role(user.getRole() != null ? user.getRole().getName() : null)
                .onBoarded(user.getOnBoarded())
                .active(user.getActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    public static UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
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
                .roleId(user.getRole() != null ? user.getRole().getId() : null)
                .role(user.getRole() != null ? user.getRole().getName() : null)
                .onBoarded(user.getOnBoarded())
                .active(user.getActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .createdBy(user.getCreatedBy())
                .updatedBy(user.getUpdatedBy())
                .build();
    }
}

