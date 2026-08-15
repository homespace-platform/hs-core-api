package com.hs.user.service.impl;

import java.util.List;

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
import com.hs.user.dto.request.UpsertPermissionRequest;
import com.hs.user.dto.response.PermissionResponse;
import com.hs.user.mapper.PermissionMapper;
import com.hs.user.model.Permission;
import com.hs.user.repository.PermissionRepository;
import com.hs.user.service.PermissionService;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@Transactional
public class PermissionServiceImpl implements PermissionService {

    PermissionRepository permissionRepository;

    @Override
    public void createPermission(UpsertPermissionRequest upsertPermissionRequest) {
        String name = upsertPermissionRequest.name().toUpperCase();

        if (permissionRepository.existsByName(name)) {
            throw new AppException(ErrorCode.PERMISSION_EXISTED);
        }

        permissionRepository.save(PermissionMapper.mapToPermission(upsertPermissionRequest));
    }

    @Override
    public void deletePermissionById(String id) {
        Permission permission = permissionRepository
                .findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PERMISSION_NOT_EXISTED));

        permissionRepository.delete(permission);
    }

    @Transactional(readOnly = true)
    @Override
    public Page<@NonNull PermissionResponse> findAllPermissions(Pageable pageable) {
        return permissionRepository
                .findAll(pageable)
                .map(PermissionMapper::mapToPermissionResponse);
    }

    @Transactional(readOnly = true)
    @Override
    public List<PermissionResponse> findAllPermissions() {
        return permissionRepository
                .findAll()
                .stream()
                .map(PermissionMapper::mapToPermissionResponse)
                .toList();
    }

    @Override
    public void updatePermission(String id, UpsertPermissionRequest upsertPermissionRequest) {
        Permission permission = permissionRepository
                .findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PERMISSION_NOT_EXISTED));

        String name = upsertPermissionRequest.name().toUpperCase();

        if (permissionRepository.existsByNameAndIdNot(name, permission.getId())) {
            throw new AppException(ErrorCode.PERMISSION_EXISTED);
        }

        PermissionMapper.updatePermissionFromRequest(permission, upsertPermissionRequest);
        permissionRepository.save(permission);
    }
}

