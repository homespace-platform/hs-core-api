package com.hs.user.mapper;

import com.hs.user.dto.request.UpdatePermissionRequest;
import com.hs.user.dto.response.PermissionResponse;
import com.hs.user.model.Permission;

public class PermissionMapper {

        public static PermissionResponse mapToPermissionResponse(Permission permission) {
                return PermissionResponse
                                .builder()
                                .id(permission.getId().toString())
                                .name(permission.getName())
                                .description(permission.getDescription())
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

