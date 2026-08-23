package com.hs.user.service;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import com.hs.user.dto.request.UpdatePermissionRequest;
import com.hs.user.dto.response.PermissionResponse;

public interface PermissionService {
    Page<@NonNull PermissionResponse> findAllPermissions(Pageable pageable);

    @Transactional(readOnly = true)
    List<PermissionResponse> findAllPermissions();

    PermissionResponse findById(String id);

    PermissionResponse updatePermission(String id, UpdatePermissionRequest updatePermissionRequest);
}

