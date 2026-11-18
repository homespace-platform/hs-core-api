package com.hs.user.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.request.UpdateRoleRequest;
import com.hs.user.model.Permission;
import com.hs.user.model.Role;
import com.hs.user.notification.BroadcastNotificationService;
import com.hs.user.repository.PermissionRepository;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleServiceImplTest {
    final RoleRepository roles = mock(RoleRepository.class);
    final PermissionRepository permissions = mock(PermissionRepository.class);
    final BroadcastNotificationService broadcasts = mock(BroadcastNotificationService.class);
    final RoleServiceImpl service = new RoleServiceImpl(roles, permissions, mock(UserRepository.class), broadcasts);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "actor", "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static Permission perm(String name) {
        var p = new Permission();
        p.setId(name);
        p.setName(name);
        return p;
    }

    private Role role(String id, String name, Permission... held) {
        var r = new Role();
        r.setId(id);
        r.setName(name);
        r.setPermissions(new HashSet<>(Set.of(held)));
        when(roles.findByIdAndActiveTrue(id)).thenReturn(Optional.of(r));
        when(roles.save(any(Role.class))).thenAnswer(i -> i.getArgument(0));
        return r;
    }

    private void permissionsExist(Permission... all) {
        when(permissions.findAllById(any())).thenAnswer(i -> {
            Set<String> ids = new HashSet<>();
            ((Iterable<?>) i.getArgument(0)).forEach(id -> ids.add((String) id));
            return Arrays.stream(all).filter(p -> ids.contains(p.getId())).toList();
        });
    }

    private void assertCode(UserErrorCode expected, Runnable action) {
        var ex = assertThrows(AppException.class, action::run);
        assertEquals(expected.getCode(), ex.getCode());
    }

    @Test void adminRolePermissionSetCannotBeChangedButDescriptionCanBeEdited() {
        var a = perm("USER_VIEW");
        var b = perm("ROLE_VIEW");
        var admin = role("r1", "ADMIN", a, b);
        permissionsExist(a, b);
        login("ADMIN", "ROLE_UPDATE", "USER_VIEW", "ROLE_VIEW");

        assertCode(UserErrorCode.ROLE_PERMISSIONS_LOCKED,
                () -> service.updateRole("r1", new UpdateRoleRequest("x", Set.of("USER_VIEW"))));
        assertEquals(2, admin.getPermissions().size());

        service.updateRole("r1", new UpdateRoleRequest("new description", Set.of("USER_VIEW", "ROLE_VIEW")));
        assertEquals("new description", admin.getDescription());
    }

    @Test void userRoleOnlyAcceptsUserScopePermissions() {
        var adminScope = perm("USER_VIEW");
        var userScope = perm("KYC_SUBMIT");
        var user = role("r2", "USER");
        permissionsExist(adminScope, userScope);
        login("ADMIN", "ROLE_UPDATE", "USER_VIEW", "KYC_SUBMIT");

        assertCode(UserErrorCode.ROLE_PERMISSION_SCOPE_INVALID,
                () -> service.updateRole("r2", new UpdateRoleRequest(null, Set.of("USER_VIEW", "KYC_SUBMIT"))));
        assertEquals(0, user.getPermissions().size());

        service.updateRole("r2", new UpdateRoleRequest(null, Set.of("KYC_SUBMIT")));
        assertEquals(Set.of(userScope), user.getPermissions());
        service.updateRole("r2", new UpdateRoleRequest(null, Set.of()));
        assertEquals(0, user.getPermissions().size());
    }

    @Test void userScopeGrantStillRequiresActorToHoldIt() {
        role("r5", "USER");
        permissionsExist(perm("KYC_SUBMIT"));
        login("ADMIN", "ROLE_UPDATE");

        assertCode(UserErrorCode.PERMISSION_NOT_HELD,
                () -> service.updateRole("r5", new UpdateRoleRequest(null, Set.of("KYC_SUBMIT"))));
    }

    @Test void otherRolesOnlyGrantPermissionsTheActorHolds() {
        var a = perm("USER_VIEW");
        var b = perm("LISTING_VIEW");
        var custom = role("r3", "SUPPORT");
        permissionsExist(a, b);
        login("ADMIN", "ROLE_UPDATE", "USER_VIEW");

        assertCode(UserErrorCode.PERMISSION_NOT_HELD,
                () -> service.updateRole("r3", new UpdateRoleRequest(null, Set.of("USER_VIEW", "LISTING_VIEW"))));
        service.updateRole("r3", new UpdateRoleRequest(null, Set.of("USER_VIEW")));
        assertEquals(Set.of(a), custom.getPermissions());
    }

    @Test void permissionSetChangeNotifiesRoleButNoOpAndDescriptionEditDoNot() {
        var a = perm("KYC_SUBMIT");
        role("r6", "USER", a);
        permissionsExist(a);
        login("ADMIN", "ROLE_UPDATE", "KYC_SUBMIT");

        service.updateRole("r6", new UpdateRoleRequest("only description", null));
        service.updateRole("r6", new UpdateRoleRequest("same set", Set.of("KYC_SUBMIT")));
        verify(broadcasts, never()).enqueueRolePermissionsChanged(any());

        service.updateRole("r6", new UpdateRoleRequest(null, Set.of()));
        verify(broadcasts).enqueueRolePermissionsChanged("USER");
    }

    @Test void rejectedChangeDoesNotNotify() {
        var a = perm("USER_VIEW");
        role("r7", "USER");
        permissionsExist(a);
        login("ADMIN", "ROLE_UPDATE", "USER_VIEW");
        assertCode(UserErrorCode.ROLE_PERMISSION_SCOPE_INVALID,
                () -> service.updateRole("r7", new UpdateRoleRequest(null, Set.of("USER_VIEW"))));
        verify(broadcasts, never()).enqueueRolePermissionsChanged(any());
    }

    @Test void unknownOrInactivePermissionIdsAreRejected() {
        var inactive = perm("OLD");
        inactive.setActive(false);
        role("r4", "SUPPORT");
        permissionsExist(inactive);
        login("ADMIN", "ROLE_UPDATE", "OLD");

        assertCode(UserErrorCode.PERMISSION_NOT_EXISTED,
                () -> service.updateRole("r4", new UpdateRoleRequest(null, Set.of("OLD"))));
        assertCode(UserErrorCode.PERMISSION_NOT_EXISTED,
                () -> service.updateRole("r4", new UpdateRoleRequest(null, Set.of("MISSING"))));
        verify(roles, never()).save(any());
    }
}
