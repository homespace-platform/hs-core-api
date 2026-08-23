package com.hs.user.mapper;

import java.util.Map;

import com.hs.user.dto.request.UpdatePermissionRequest;
import com.hs.user.dto.response.PermissionResponse;
import com.hs.user.model.Permission;
import com.hs.user.model.User;

public class PermissionMapper {

        public static PermissionResponse mapToPermissionResponse(Permission permission) {
                return mapToPermissionResponse(permission, Map.of());
        }

        public static PermissionResponse mapToPermissionResponse(Permission permission, Map<String, User> actors) {
                return PermissionResponse
                                .builder()
                                .id(permission.getId())
                                .name(permission.getName())
                                .description(permission.getDescription())
                                .active(permission.getActive())
                                .createdAt(permission.getCreatedAt())
                                .updatedAt(permission.getUpdatedAt())
                                .createdBy(UserMapper.resolveActor(permission.getCreatedBy(), actors))
                                .updatedBy(UserMapper.resolveActor(permission.getUpdatedBy(), actors))
                                .build();
        }

        public static void updatePermissionFromRequest(
                        Permission permission,
                        UpdatePermissionRequest updatePermissionRequest) {
                if (updatePermissionRequest.description() != null) {
                        permission.setDescription(updatePermissionRequest.description().isBlank()
                                        ? null
                                        : updatePermissionRequest.description());
                }
        }

}
