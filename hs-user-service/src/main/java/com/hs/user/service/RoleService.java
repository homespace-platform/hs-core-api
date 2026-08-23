package com.hs.user.service;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import com.hs.user.dto.request.UpdateRoleRequest;
import com.hs.user.dto.response.RoleResponse;

public interface RoleService {
    @Transactional(readOnly = true)
    Page<@NonNull RoleResponse> findAllRoles(Pageable pageable, boolean includePermissions);

    @Transactional(readOnly = true)
    List<RoleResponse> findAllRoles();

    RoleResponse findById(String id);

    RoleResponse updateRole(String id, UpdateRoleRequest updateRoleRequest);
}

