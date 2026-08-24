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
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.request.UpdateRoleRequest;
import com.hs.user.dto.response.RoleResponse;
import com.hs.user.mapper.RoleMapper;
import com.hs.user.model.Permission;
import com.hs.user.model.Role;
import com.hs.user.model.User;
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

        if (updateRoleRequest.permissionIdList() != null) {
            role.setPermissions(getPermissions(updateRoleRequest.permissionIdList()));
        }

        Role saved = roleRepository.save(role);
        return RoleMapper.mapToRoleResponse(saved, true, loadActors(List.of(saved)));
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
