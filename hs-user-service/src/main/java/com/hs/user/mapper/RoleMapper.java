package com.hs.user.mapper;

import java.util.Collections;
import java.util.stream.Collectors;

import com.hs.user.dto.request.UpsertRoleRequest;
import com.hs.user.dto.response.RoleResponse;
import com.hs.user.model.Role;

public class RoleMapper {

    public static RoleResponse mapToRoleResponse(Role role) {
        return RoleResponse
                .builder()
                .id(role.getId().toString())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(role.getPermissions() == null
                        ? Collections.emptySet()
                        : role.getPermissions()
                                .stream()
                                .map(PermissionMapper::mapToPermissionResponse)
                                .collect(Collectors.toSet()))
                .build();
    }

    public static Role mapToRole(UpsertRoleRequest upsertRoleRequest) {
        String name = upsertRoleRequest.name().toUpperCase();
        String description =
                upsertRoleRequest.description() != null && !upsertRoleRequest.description().isBlank()
                        ? upsertRoleRequest.description()
                        : null;

        return Role
                .builder()
                .name(name)
                .description(description)
                .build();
    }

    public static void updateRoleFromRequest(Role role, UpsertRoleRequest upsertRoleRequest) {
        String name = upsertRoleRequest.name().toUpperCase();
        String description = upsertRoleRequest.description() != null && !upsertRoleRequest.description().isBlank()
                ? upsertRoleRequest.description()
                : null;

        role.setName(name);
        role.setDescription(description);
    }

}

