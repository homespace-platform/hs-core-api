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
import com.hs.user.dto.request.UpdatePermissionRequest;
import com.hs.user.dto.response.PermissionResponse;
import com.hs.user.mapper.PermissionMapper;
import com.hs.user.model.Permission;
import com.hs.user.model.User;
import com.hs.user.repository.PermissionRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.PermissionService;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@Transactional
public class PermissionServiceImpl implements PermissionService {

    PermissionRepository permissionRepository;
    UserRepository userRepository;

    @Transactional(readOnly = true)
    @Override
    public Page<@NonNull PermissionResponse> findAllPermissions(Pageable pageable) {
        Page<Permission> permissions = permissionRepository.findAllByActiveTrue(pageable);
        Map<String, User> actors = loadActors(permissions.getContent());
        return permissions.map(permission -> PermissionMapper.mapToPermissionResponse(permission, actors));
    }

    @Transactional(readOnly = true)
    @Override
    public List<PermissionResponse> findAllPermissions() {
        List<Permission> permissions = permissionRepository.findAllByActiveTrue();
        Map<String, User> actors = loadActors(permissions);
        return permissions.stream()
                .map(permission -> PermissionMapper.mapToPermissionResponse(permission, actors))
                .toList();
    }

    @Transactional(readOnly = true)
    @Override
    public PermissionResponse findById(String id) {
        Permission permission = permissionRepository
                .findByIdAndActiveTrue(id)
                .orElseThrow(() -> new AppException(UserErrorCode.PERMISSION_NOT_EXISTED));
        return PermissionMapper.mapToPermissionResponse(permission, loadActors(List.of(permission)));
    }

    @Override
    public PermissionResponse updatePermission(String id, UpdatePermissionRequest updatePermissionRequest) {
        Permission permission = permissionRepository
                .findByIdAndActiveTrue(id)
                .orElseThrow(() -> new AppException(UserErrorCode.PERMISSION_NOT_EXISTED));

        PermissionMapper.updatePermissionFromRequest(permission, updatePermissionRequest);
        Permission saved = permissionRepository.save(permission);
        return PermissionMapper.mapToPermissionResponse(saved, loadActors(List.of(saved)));
    }

    private Map<String, User> loadActors(List<Permission> permissions) {
        Set<String> actorIds = new HashSet<>();
        for (Permission permission : permissions) {
            if (permission.getCreatedBy() != null && !permission.getCreatedBy().isBlank()) {
                actorIds.add(permission.getCreatedBy());
            }
            if (permission.getUpdatedBy() != null && !permission.getUpdatedBy().isBlank()) {
                actorIds.add(permission.getUpdatedBy());
            }
        }
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
