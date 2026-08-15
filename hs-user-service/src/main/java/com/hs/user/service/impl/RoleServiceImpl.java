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

import com.hs.user.advice.base.AppException;
import com.hs.user.constant.base.ErrorCode;
import com.hs.user.dto.request.UpsertRoleRequest;
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

    @Override
    public void createRole(UpsertRoleRequest upsertRoleRequest) {
        String name = upsertRoleRequest.name().toUpperCase();

        if (roleRepository.existsByName(name))
            throw new AppException(ErrorCode.ROLE_EXISTED);

        Role role = RoleMapper.mapToRole(upsertRoleRequest);

        if (upsertRoleRequest.permissionIdList() != null && !upsertRoleRequest.permissionIdList().isEmpty()) {
            role.setPermissions(getPermissions(upsertRoleRequest.permissionIdList()));
        }

        roleRepository.save(role);
    }

    @Transactional(readOnly = true)
    @Override
    public Page<@NonNull RoleResponse> findAllRoles(Pageable pageable) {
        return roleRepository
                .findAll(pageable)
                .map(RoleMapper::mapToRoleResponse);
    }

    @Transactional(readOnly = true)
    @Override
    public List<RoleResponse> findAllRoles() {
        return roleRepository
                .findAll()
                .stream()
                .map(RoleMapper::mapToRoleResponse)
                .toList();
    }

    @Override
    public RoleResponse findById(String id) {
        return roleRepository
                .findById(id)
                .map(RoleMapper::mapToRoleResponse)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_EXISTED));
    }

    @Override
    public void updateRole(String id, UpsertRoleRequest upsertRoleRequest) {
        Role role = roleRepository
                .findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_EXISTED));

        String name = upsertRoleRequest.name().toUpperCase();

        if (roleRepository.existsByNameAndIdNot(name, role.getId()))
            throw new AppException(ErrorCode.ROLE_EXISTED);

        RoleMapper.updateRoleFromRequest(role, upsertRoleRequest);

        if (upsertRoleRequest.permissionIdList() != null && !upsertRoleRequest.permissionIdList().isEmpty()) {
            role.setPermissions(getPermissions(upsertRoleRequest.permissionIdList()));
        }

        roleRepository.save(role);
    }

    @Override
    public void deleteRoleById(String id) {
        Role role = roleRepository
                .findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_EXISTED));

        roleRepository.delete(role);
    }

    private Set<Permission> getPermissions(Set<String> permissionIds) {
        Set<Permission> permissions = new HashSet<>(permissionRepository.findAllById(permissionIds));

        if (permissions.size() != permissionIds.size()) {
            throw new AppException(ErrorCode.PERMISSION_NOT_EXISTED);
        }

        return permissions;
    }

}

