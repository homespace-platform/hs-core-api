package com.hs.user.service;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import com.hs.user.dto.request.UpsertPermissionRequest;
import com.hs.user.dto.response.PermissionResponse;

public interface PermissionService {
    void createPermission(UpsertPermissionRequest upsertPermissionRequest);

    Page<@NonNull PermissionResponse> findAllPermissions(Pageable pageable);

    @Transactional(readOnly = true)
    List<PermissionResponse> findAllPermissions();

    void updatePermission(String id, UpsertPermissionRequest upsertPermissionRequest);

    void deletePermissionById(String id);
}

