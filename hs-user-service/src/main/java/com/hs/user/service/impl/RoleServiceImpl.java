package com.hs.user.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.CurrentUser;
import com.hs.common.security.Permissions;
import com.hs.user.constant.RoleConstants;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.request.UpdateRoleRequest;
import com.hs.user.dto.response.RoleResponse;
import com.hs.user.mapper.RoleMapper;
import com.hs.user.model.Permission;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.notification.BroadcastNotificationService;
import com.hs.user.repository.PermissionRepository;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.RoleService;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@Transactional
public class RoleServiceImpl implements RoleService {

    RoleRepository roleRepository;
    PermissionRepository permissionRepository;
    UserRepository userRepository;
    BroadcastNotificationService broadcasts;

    @Transactional(readOnly = true)
    @Override
    public Page<@NonNull RoleResponse> findAllRoles(Pageable pageable, boolean includePermissions) {
        Page<Role> roles = roleRepository.findAllByActiveTrue(pageable);
        Map<String, User> actors = loadActors(roles.getContent());
        return roles.map(role -> RoleMapper.mapToRoleResponse(role, includePermissions, actors));
    }

    @Transactional(readOnly = true)
    @Override
    public List<RoleResponse> findAllRoles() {
        List<Role> roles = roleRepository.findAllByActiveTrue();
        Map<String, User> actors = loadActors(roles);
        return roles.stream()
                .map(role -> RoleMapper.mapToRoleResponse(role, false, actors))
                .toList();
    }

    @Override
    public RoleResponse findById(String id) {
        Role role = roleRepository
                .findByIdAndActiveTrue(id)
                .orElseThrow(() -> new AppException(UserErrorCode.ROLE_NOT_EXISTED));
        return RoleMapper.mapToRoleResponse(role, true, loadActors(List.of(role)));
    }

    @Override
    public RoleResponse updateRole(String id, UpdateRoleRequest updateRoleRequest) {
        Role role = roleRepository
                .findByIdAndActiveTrue(id)
                .orElseThrow(() -> new AppException(UserErrorCode.ROLE_NOT_EXISTED));

        RoleMapper.updateRoleFromRequest(role, updateRoleRequest);

        boolean permissionsChanged = false;
        if (updateRoleRequest.permissionIdList() != null) {
            Set<Permission> requested = getPermissions(updateRoleRequest.permissionIdList());
            assertPermissionChangeAllowed(role, requested);
            Set<String> before = role.getPermissions() == null ? Set.of()
                    : role.getPermissions().stream().map(Permission::getId).collect(Collectors.toSet());
            permissionsChanged = !before.equals(requested.stream().map(Permission::getId).collect(Collectors.toSet()));
            role.setPermissions(requested);
        }

        Role saved = roleRepository.save(role);
        if (permissionsChanged) {
            broadcasts.enqueueRolePermissionsChanged(saved.getName());
        }
        return RoleMapper.mapToRoleResponse(saved, true, loadActors(List.of(saved)));
    }

    /** ADMIN is managed by the seed; USER only gets user-scope permissions; other roles only grant what the actor holds. */
    private void assertPermissionChangeAllowed(Role role, Set<Permission> requested) {
        Set<String> current = role.getPermissions() == null ? Set.of()
                : role.getPermissions().stream().map(Permission::getId).collect(Collectors.toSet());
        Set<Permission> added = requested.stream()
                .filter(permission -> !current.contains(permission.getId()))
                .collect(Collectors.toSet());
        if (RoleConstants.ADMIN.equals(role.getName())) {
            // Unchanged set is tolerated so the description can still be edited.
            if (!added.isEmpty() || current.size() != requested.size()) {
                throw new AppException(UserErrorCode.ROLE_PERMISSIONS_LOCKED);
            }
            return;
        }
        if (RoleConstants.USER.equals(role.getName())
                && added.stream().anyMatch(permission -> !Permissions.isUserScope(permission.getName()))) {
            throw new AppException(UserErrorCode.ROLE_PERMISSION_SCOPE_INVALID);
        }
        if (added.stream().anyMatch(permission -> !CurrentUser.hasAuthority(permission.getName()))) {
            throw new AppException(UserErrorCode.PERMISSION_NOT_HELD);
        }
    }

    private Set<Permission> getPermissions(Set<String> permissionIds) {
        Set<Permission> permissions = new HashSet<>(permissionRepository.findAllById(permissionIds));

        if (permissions.size() != permissionIds.size()
                || permissions.stream().anyMatch(permission -> !Boolean.TRUE.equals(permission.getActive()))) {
            throw new AppException(UserErrorCode.PERMISSION_NOT_EXISTED);
        }

        return permissions;
    }

    private Map<String, User> loadActors(List<Role> roles) {
        Set<String> actorIds = new HashSet<>();
        for (Role role : roles) {
            addActorId(actorIds, role.getCreatedBy());
            addActorId(actorIds, role.getUpdatedBy());
            if (role.getPermissions() == null) {
                continue;
            }
            for (Permission permission : role.getPermissions()) {
                addActorId(actorIds, permission.getCreatedBy());
                addActorId(actorIds, permission.getUpdatedBy());
            }
        }
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private void addActorId(Set<String> actorIds, String actorId) {
        if (actorId != null && !actorId.isBlank()) {
            actorIds.add(actorId);
        }
    }

}
