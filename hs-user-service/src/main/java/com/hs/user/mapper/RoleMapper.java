package com.hs.user.mapper;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.hs.user.dto.request.UpdateRoleRequest;
import com.hs.user.dto.response.PermissionResponse;
import com.hs.user.dto.response.RoleResponse;
import com.hs.user.model.Role;
import com.hs.user.model.User;

public class RoleMapper {

    public static RoleResponse mapToRoleResponse(Role role) {
        return mapToRoleResponse(role, true, Map.of());
    }

    public static RoleResponse mapToRoleResponse(
            Role role, boolean includePermissions, Map<String, User> actors) {
        return RoleResponse
                .builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(includePermissions ? mapPermissions(role, actors) : null)
                .active(role.getActive())
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .createdBy(UserMapper.resolveActor(role.getCreatedBy(), actors))
                .updatedBy(UserMapper.resolveActor(role.getUpdatedBy(), actors))
                .build();
    }

    private static Set<PermissionResponse> mapPermissions(Role role, Map<String, User> actors) {
        if (role.getPermissions() == null) {
            return Collections.emptySet();
        }
        return role.getPermissions()
                .stream()
                .filter(permission -> Boolean.TRUE.equals(permission.getActive()))
                .map(permission -> PermissionMapper.mapToPermissionResponse(permission, actors))
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
