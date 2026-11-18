package com.hs.user.config.database;

import com.hs.common.security.Permissions;
import com.hs.user.model.Permission;
import com.hs.user.model.Role;
import com.hs.user.repository.PermissionRepository;
import com.hs.user.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionDataInitializerTest {
    private static Permission permission(String name, String description) {
        var p = new Permission();
        p.setId(name);
        p.setName(name);
        p.setDescription(description);
        return p;
    }

    @Test void seedsWholeCatalogKeepsLegacyRowsAndGrantsEverythingToAdmin() {
        var permissions = mock(PermissionRepository.class);
        var roles = mock(RoleRepository.class);
        var legacy = permission("LEGACY_PERMISSION", "old");
        var blank = permission(Permissions.USER_VIEW, " ");
        var kept = permission(Permissions.USER_CREATE, "custom text");
        when(permissions.findAll()).thenReturn(new ArrayList<>(List.of(legacy, blank, kept)));
        when(permissions.save(any(Permission.class))).thenAnswer(i -> i.getArgument(0));
        var admin = new Role();
        admin.setName("ADMIN");
        admin.setPermissions(new HashSet<>(Set.of(legacy)));
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(admin));

        new PermissionDataInitializer(permissions, roles).run();

        ArgumentCaptor<Permission> saved = ArgumentCaptor.forClass(Permission.class);
        verify(permissions, atLeastOnce()).save(saved.capture());
        Set<String> savedNames = saved.getAllValues().stream().map(Permission::getName).collect(Collectors.toSet());
        assertTrue(savedNames.contains(Permissions.KYC_VIEW), "missing rows are inserted");
        assertFalse(savedNames.contains(Permissions.USER_CREATE), "row with a description is untouched");
        assertEquals(Permissions.CATALOG.get(Permissions.USER_VIEW), blank.getDescription());
        assertEquals("custom text", kept.getDescription());

        Set<String> adminNames = admin.getPermissions().stream().map(Permission::getName).collect(Collectors.toSet());
        assertTrue(adminNames.containsAll(Permissions.CATALOG.keySet()));
        assertTrue(adminNames.contains("LEGACY_PERMISSION"), "extra legacy rows do not crash or get dropped");
        verify(roles).save(admin);
    }

    @Test void userGetsUserScopeOnlyForRowsCreatedThisStartup() {
        var permissions = mock(PermissionRepository.class);
        var roles = mock(RoleRepository.class);
        // KYC_SUBMIT already exists (admin may have revoked it from USER); every other catalog row is new.
        var existingUserScope = permission(Permissions.KYC_SUBMIT, "x");
        when(permissions.findAll()).thenReturn(new ArrayList<>(List.of(existingUserScope)));
        when(permissions.save(any(Permission.class))).thenAnswer(i -> i.getArgument(0));
        var admin = new Role();
        admin.setName("ADMIN");
        var user = new Role();
        user.setName("USER");
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(admin));
        when(roles.findByName("USER")).thenReturn(Optional.of(user));

        new PermissionDataInitializer(permissions, roles).run();

        Set<String> userNames = user.getPermissions().stream().map(Permission::getName).collect(Collectors.toSet());
        Set<String> expected = new HashSet<>(Permissions.USER_SCOPE);
        expected.remove(Permissions.KYC_SUBMIT);
        assertEquals(expected, userNames);
        verify(roles).save(user);
    }

    @Test void userRoleUntouchedWhenNoUserScopeRowIsNew() {
        var permissions = mock(PermissionRepository.class);
        var roles = mock(RoleRepository.class);
        var all = Permissions.CATALOG.keySet().stream().map(n -> permission(n, "d")).toList();
        when(permissions.findAll()).thenReturn(new ArrayList<>(all));
        var admin = new Role();
        admin.setName("ADMIN");
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(admin));

        new PermissionDataInitializer(permissions, roles).run();

        verify(roles, org.mockito.Mockito.never()).findByName("USER");
    }
}
