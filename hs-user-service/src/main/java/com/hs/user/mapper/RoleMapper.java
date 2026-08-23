package com.hs.user.mapper;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import com.hs.user.dto.request.UpdateRoleRequest;
import com.hs.user.dto.response.PermissionResponse;
import com.hs.user.dto.response.RoleResponse;
import com.hs.user.model.Role;

public class RoleMapper {

    public static RoleResponse mapToRoleResponse(Role role) {
        return mapToRoleResponse(role, true);
    }

    public static RoleResponse mapToRoleResponse(Role role, boolean includePermissions) {
        return RoleResponse
                .builder()
                .id(role.getId().toString())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(includePermissions ? mapPermissions(role) : null)
                .build();
    }

    private static Set<PermissionResponse> mapPermissions(Role role) {
        if (role.getPermissions() == null) {
            return Collections.emptySet();
        }
        return role.getPermissions()
                .stream()
                .map(PermissionMapper::mapToPermissionResponse)
                .collect(Collectors.toSet());
    }

    public static void updateRoleFromRequest(Role role, UpdateRoleRequest updateRoleRequest) {
        if (updateRoleRequest.description() != null) {
            role.setDescription(updateRoleRequest.description().isBlank()
                    ? null
                    : updateRoleRequest.description());
        }
    }

}

