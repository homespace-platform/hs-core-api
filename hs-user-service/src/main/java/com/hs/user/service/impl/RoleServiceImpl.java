package com.hs.user.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import com.hs.user.repository.PermissionRepository;
import com.hs.user.repository.RoleRepository;
import com.hs.user.service.RoleService;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@Transactional
public class RoleServiceImpl implements RoleService {

    RoleRepository roleRepository;
    PermissionRepository permissionRepository;

    @Transactional(readOnly = true)
    @Override
    public Page<@NonNull RoleResponse> findAllRoles(Pageable pageable, boolean includePermissions) {
        return roleRepository
                .findAll(pageable)
                .map(role -> RoleMapper.mapToRoleResponse(role, includePermissions));
    }

    @Transactional(readOnly = true)
    @Override
    public List<RoleResponse> findAllRoles() {
        return roleRepository
                .findAll()
                .stream()
                .map(role -> RoleMapper.mapToRoleResponse(role, false))
                .toList();
    }

    @Override
    public RoleResponse findById(String id) {
        return roleRepository
                .findById(id)
                .map(RoleMapper::mapToRoleResponse)
                .orElseThrow(() -> new AppException(UserErrorCode.ROLE_NOT_EXISTED));
    }

    @Override
    public void updateRole(String id, UpdateRoleRequest updateRoleRequest) {
        Role role = roleRepository
                .findById(id)
                .orElseThrow(() -> new AppException(UserErrorCode.ROLE_NOT_EXISTED));

        RoleMapper.updateRoleFromRequest(role, updateRoleRequest);

        if (updateRoleRequest.permissionIdList() != null) {
            role.setPermissions(getPermissions(updateRoleRequest.permissionIdList()));
        }

        roleRepository.save(role);
    }

    @Override
    public void deleteRoleById(String id) {
        Role role = roleRepository
                .findById(id)
                .orElseThrow(() -> new AppException(UserErrorCode.ROLE_NOT_EXISTED));

        roleRepository.delete(role);
    }

    private Set<Permission> getPermissions(Set<String> permissionIds) {
        Set<Permission> permissions = new HashSet<>(permissionRepository.findAllById(permissionIds));

        if (permissions.size() != permissionIds.size()) {
            throw new AppException(UserErrorCode.PERMISSION_NOT_EXISTED);
        }

        return permissions;
    }

}

