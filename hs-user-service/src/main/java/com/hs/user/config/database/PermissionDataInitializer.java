package com.hs.user.config.database;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hs.user.constant.PermissionConstants;
import com.hs.user.constant.RoleConstants;
import com.hs.user.model.Permission;
import com.hs.user.model.Role;
import com.hs.user.repository.PermissionRepository;
import com.hs.user.repository.RoleRepository;

import lombok.RequiredArgsConstructor;

@Component
@Order(2)
@RequiredArgsConstructor
public class PermissionDataInitializer implements CommandLineRunner {

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;

    @Override
    @Transactional
    public void run(String... args) {
        Map<String, String> permissions = Map.ofEntries(
                Map.entry(PermissionConstants.Admin.USER_VIEW, "View users"),
                Map.entry(PermissionConstants.Admin.USER_CREATE, "Create users"),
                Map.entry(PermissionConstants.Admin.USER_UPDATE, "Update users"),
                Map.entry(PermissionConstants.Admin.ROLE_VIEW, "View roles"),
                Map.entry(PermissionConstants.Admin.ROLE_CREATE, "Create roles"),
                Map.entry(PermissionConstants.Admin.ROLE_UPDATE, "Update roles"),
                Map.entry(PermissionConstants.Admin.ROLE_DELETE, "Delete roles"),
                Map.entry(PermissionConstants.Admin.PERMISSION_VIEW, "View permissions"),
                Map.entry(PermissionConstants.Admin.PERMISSION_CREATE, "Create permissions"),
                Map.entry(PermissionConstants.Admin.PERMISSION_UPDATE, "Update permissions"),
                Map.entry(PermissionConstants.Admin.PERMISSION_DELETE, "Delete permissions"));

        permissions.forEach((name, description) -> {
            if (!permissionRepository.existsByName(name)) {
                permissionRepository.save(Permission.builder()
                        .name(name)
                        .description(description)
                        .build());
            }
        });

        assignAllPermissionsToAdmin(permissions.keySet());
    }

    private void assignAllPermissionsToAdmin(Set<String> permissionNames) {
        Role adminRole = roleRepository
                .findByName(RoleConstants.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Missing default role: " + RoleConstants.ADMIN));

        Set<Permission> adminPermissions = permissionRepository
                .findAll()
                .stream()
                .filter(permission -> permissionNames.contains(permission.getName()))
                .collect(Collectors.toSet());

        if (adminPermissions.size() != permissionNames.size()) {
            throw new IllegalStateException("Missing seeded permissions for admin role");
        }

        Set<Permission> currentPermissions = adminRole.getPermissions() == null
                ? new HashSet<>()
                : new HashSet<>(adminRole.getPermissions());

        currentPermissions.addAll(adminPermissions);
        adminRole.setPermissions(currentPermissions);
        roleRepository.save(adminRole);
    }
}

