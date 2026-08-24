package com.hs.user.mapper;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.hs.user.dto.response.UserAuditActorResponse;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.dto.response.UserResponse;
import com.hs.user.model.Address;
import com.hs.user.model.User;

public class UserMapper {

    public static UserProfileResponse mapToUserProfileResponse(User user) {
        return mapToUserProfileResponse(user, null);
    }

    public static UserProfileResponse mapToUserProfileResponse(User user, Address address) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .avatarUrl(user.getAvatarUrl())
                .avatarStorageId(user.getAvatarStorageId())
                .phone(user.getPhone())
                .dob(user.getDob())
                .gender(user.getGender())
                .roleId(user.getRole() != null ? user.getRole().getId() : null)
                .role(user.getRole() != null ? user.getRole().getName() : null)
                .onBoarded(user.getOnBoarded())
                .active(user.getActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .address(AddressMapper.mapToAddressResponse(address))
                .build();
    }

    public static UserResponse mapToUserResponse(User user, Map<String, User> actors) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .avatarUrl(user.getAvatarUrl())
                .avatarStorageId(user.getAvatarStorageId())
                .phone(user.getPhone())
                .dob(user.getDob())
                .gender(user.getGender())
                .roleId(user.getRole() != null ? user.getRole().getId() : null)
                .role(user.getRole() != null ? user.getRole().getName() : null)
                .onBoarded(user.getOnBoarded())
                .active(user.getActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .createdBy(resolveActor(user.getCreatedBy(), actors))
                .updatedBy(resolveActor(user.getUpdatedBy(), actors))
                .build();
    }

    public static UserAuditActorResponse resolveActor(String actorId, Map<String, User> actors) {
        if (actorId == null || actorId.isBlank()) {
            return null;
        }

        User actor = actors == null ? null : actors.get(actorId);
        if (actor == null) {
            return UserAuditActorResponse.builder().id(actorId).build();
        }

        String fullName = Stream.of(actor.getFirstName(), actor.getLastName())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));

        return UserAuditActorResponse.builder()
                .id(actor.getId())
                .fullName(fullName.isBlank() ? null : fullName)
                .username(actor.getUsername())
                .phone(actor.getPhone())
                .email(actor.getEmail())
                .build();
    }
}
