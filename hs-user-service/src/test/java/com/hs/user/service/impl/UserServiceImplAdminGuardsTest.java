package com.hs.user.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.request.AdminUpdateUserRequest;
import com.hs.user.model.Permission;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.notification.AccountNotificationWriter;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.KeycloakUserService;
import com.hs.user.utils.CurrentUserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserServiceImplAdminGuardsTest {
    final UserRepository users = mock(UserRepository.class);
    final RoleRepository roles = mock(RoleRepository.class);
    final KeycloakUserService keycloak = mock(KeycloakUserService.class);
    final CurrentUserUtils actor = mock(CurrentUserUtils.class);
    final AccountNotificationWriter notifications = mock(AccountNotificationWriter.class);
    final UserServiceImpl service = new UserServiceImpl(users, roles, mock(AddressRepository.class),
            mock(KycVerificationRepository.class), keycloak, actor, notifications);

    final Role adminRole = role("role-admin", "ADMIN", "USER_VIEW", "ROLE_UPDATE");
    final Role userRole = role("role-user", "USER");

    @BeforeEach void actor() { when(actor.getCurrentUserId()).thenReturn("actor"); }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "actor", "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static Role role(String id, String name, String... permissionNames) {
        var r = new Role();
        r.setId(id);
        r.setName(name);
        var perms = new HashSet<Permission>();
        for (String p : permissionNames) {
            var permission = new Permission();
            permission.setId(p);
            permission.setName(p);
            perms.add(permission);
        }
        r.setPermissions(perms);
        return r;
    }

    private User user(String id, Role role) {
        var u = new User();
        u.setId(id);
        u.setActive(true);
        u.setRole(role);
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(u));
        return u;
    }

    private static void assertCode(Object expected, Runnable action) {
        var ex = assertThrows(AppException.class, action::run);
        int code = expected instanceof UserErrorCode u ? u.getCode() : ((ErrorCode) expected).getCode();
        assertEquals(code, ex.getCode());
    }

    @Test void changingRoleRequiresRoleUpdateEvenWithUserUpdate() {
        user("target", userRole);
        when(roles.findByIdAndActiveTrue("role-x")).thenReturn(Optional.of(role("role-x", "SUPPORT")));
        login("ADMIN", "USER_UPDATE");

        assertCode(ErrorCode.UNAUTHORIZED, () -> service.updateUser("target",
                new AdminUpdateUserRequest(null, null, null, null, null, "role-x")));
        verifyNoInteractions(keycloak);
        verify(users, never()).save(any());
    }

    @Test void updateWithoutRoleChangeNeedsNoRoleUpdate() {
        var target = user("target", userRole);
        when(roles.findByIdAndActiveTrue("role-user")).thenReturn(Optional.of(userRole));
        login("ADMIN", "USER_UPDATE");

        // Same roleId as the current one is not a role change; the response mapping is out of scope here.
        try {
            service.updateUser("target", new AdminUpdateUserRequest(null, null, null, null, null, "role-user"));
        } catch (RuntimeException ignored) {
            // mapper may reject the bare fixture; the guard is what matters
        }
        verify(users).save(target);
    }

    @Test void lastActiveAdminCannotBeDisabledOrDemoted() {
        var onlyAdmin = user("a1", adminRole);
        when(users.lockActiveByRoleName("ADMIN")).thenReturn(List.of(onlyAdmin));
        when(roles.findByIdAndActiveTrue("role-user")).thenReturn(Optional.of(userRole));
        login("ADMIN", "USER_UPDATE", "ROLE_UPDATE", "USER_VIEW");

        assertCode(UserErrorCode.USER_LAST_ADMIN, () -> service.updateUserStatus("a1", false));
        assertCode(UserErrorCode.USER_LAST_ADMIN, () -> service.updateUser("a1",
                new AdminUpdateUserRequest(null, null, null, null, null, "role-user")));
        assertEquals(true, onlyAdmin.getActive());
        assertEquals(adminRole, onlyAdmin.getRole());
        verifyNoInteractions(keycloak);
    }

    @Test void adminCanBeDisabledWhenAnotherActiveAdminRemains() {
        var a1 = user("a1", adminRole);
        var a2 = new User();
        a2.setId("a2");
        when(users.lockActiveByRoleName("ADMIN")).thenReturn(List.of(a1, a2));
        login("ADMIN", "USER_UPDATE", "ROLE_UPDATE", "USER_VIEW");

        service.updateUserStatus("a1", false);

        assertFalse(a1.getActive());
    }

    @Test void adminSetIsLockedBeforeTargetRowWhenDisablingOrChangingRole() {
        var a1 = user("a1", adminRole);
        var a2 = new User();
        a2.setId("a2");
        when(users.lockActiveByRoleName("ADMIN")).thenReturn(List.of(a1, a2));
        when(roles.findByIdAndActiveTrue("role-user")).thenReturn(Optional.of(userRole));
        login("ADMIN", "USER_UPDATE", "ROLE_UPDATE", "USER_VIEW");

        service.updateUserStatus("a1", false);
        var order = org.mockito.Mockito.inOrder(users);
        order.verify(users).lockActiveByRoleName("ADMIN");
        order.verify(users).findByIdForUpdate("a1");

        a1.setActive(true);
        org.mockito.Mockito.clearInvocations(users);
        try {
            service.updateUser("a1", new AdminUpdateUserRequest(null, null, null, null, null, "role-user"));
        } catch (RuntimeException ignored) {
            // response mapping of the bare fixture is out of scope; lock order is what matters
        }
        order = org.mockito.Mockito.inOrder(users);
        order.verify(users).lockActiveByRoleName("ADMIN");
        order.verify(users).findByIdForUpdate("a1");
        verify(users, org.mockito.Mockito.times(1)).lockActiveByRoleName("ADMIN");
    }

    @Test void enablingOrNonRoleUpdateDoesNotLockAdminSet() {
        user("target", userRole);
        login("ADMIN", "USER_UPDATE", "ROLE_UPDATE", "USER_VIEW");
        service.updateUserStatus("target", true);
        verify(users, never()).lockActiveByRoleName(any());
    }

    @Test void actorCannotModifyUserHoldingPermissionsTheActorLacks() {
        user("a1", adminRole);
        login("ADMIN", "USER_UPDATE", "USER_VIEW");

        assertCode(UserErrorCode.USER_PRIVILEGE_ESCALATION, () -> service.updateUserStatus("a1", false));
        assertCode(UserErrorCode.USER_PRIVILEGE_ESCALATION, () -> service.updateUser("a1",
                new AdminUpdateUserRequest(null, null, null, null, null, null)));
        verifyNoInteractions(keycloak);
    }

    @Test void selfGuardsStillApply() {
        var self = user("actor", userRole);
        login("ADMIN", "USER_UPDATE", "ROLE_UPDATE");

        assertCode(UserErrorCode.USER_CANNOT_DISABLE_SELF, () -> service.updateUserStatus("actor", false));
        assertCode(UserErrorCode.USER_CANNOT_UPDATE_OWN_ROLE, () -> service.updateUser("actor",
                new AdminUpdateUserRequest(null, null, null, null, null, "role-admin")));
        assertEquals(userRole, self.getRole());
    }
}
