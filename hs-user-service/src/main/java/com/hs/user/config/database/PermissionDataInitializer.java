package com.hs.user.config.database;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hs.common.security.Permissions;
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
        Map<String, Permission> existing = permissionRepository.findAll().stream()
                .collect(Collectors.toMap(Permission::getName, Function.identity(), (a, b) -> a));

        Set<Permission> created = new HashSet<>();
        Permissions.CATALOG.forEach((name, description) -> {
            Permission permission = existing.get(name);
            if (permission == null) {
                Permission saved = permissionRepository.save(Permission.builder()
                        .name(name)
                        .description(description)
                        .build());
                existing.put(name, saved);
                if (Permissions.isUserScope(name)) {
                    created.add(saved);
                }
            } else if (permission.getDescription() == null || permission.getDescription().isBlank()) {
                permission.setDescription(description);
                permissionRepository.save(permission);
            }
        });

        assignAllPermissionsToAdmin(Permissions.CATALOG.keySet().stream().map(existing::get).collect(Collectors.toSet()));
        grantNewUserScopeToUser(created);
    }

    /** Only newly created rows are granted, so an admin's later revocation is never undone on restart. */
    private void grantNewUserScopeToUser(Set<Permission> created) {
        if (created.isEmpty()) {
            return;
        }
        roleRepository.findByName(RoleConstants.USER).ifPresent(userRole -> {
            Set<Permission> current = userRole.getPermissions() == null
                    ? new HashSet<>()
                    : new HashSet<>(userRole.getPermissions());
            current.addAll(created);
            userRole.setPermissions(current);
            roleRepository.save(userRole);
        });
    }

    /** ADMIN is the full-access role: it always holds every catalog permission. Extra legacy rows are left alone. */
    private void assignAllPermissionsToAdmin(Set<Permission> catalogPermissions) {
        Role adminRole = roleRepository
                .findByName(RoleConstants.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Missing default role: " + RoleConstants.ADMIN));

        Set<Permission> currentPermissions = adminRole.getPermissions() == null
                ? new HashSet<>()
                : new HashSet<>(adminRole.getPermissions());

        currentPermissions.addAll(catalogPermissions);
        adminRole.setPermissions(currentPermissions);
        roleRepository.save(adminRole);
    }
}
