package com.hs.user.mapper;

import com.hs.user.dto.request.UpsertPermissionRequest;
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

        public static Permission mapToPermission(UpsertPermissionRequest upsertPermissionRequest) {
                String name = upsertPermissionRequest.name().toUpperCase();
                String description = upsertPermissionRequest.description() != null
                                && !upsertPermissionRequest.description().isBlank()
                                                ? upsertPermissionRequest.description()
                                                : null;

                return Permission
                                .builder()
                                .name(name)
                                .description(description)
                                .build();
        }

        public static void updatePermissionFromRequest(
                        Permission permission,
                        UpsertPermissionRequest upsertPermissionRequest) {
                String name = upsertPermissionRequest.name().toUpperCase();
                String description = upsertPermissionRequest.description() != null
                                && !upsertPermissionRequest.description().isBlank()
                                                ? upsertPermissionRequest.description()
                                                : null;

                permission.setName(name);
                permission.setDescription(description);
        }

}

