package com.hs.api.controller.admin;

import com.hs.common.security.Permissions;
import com.hs.user.dto.request.AdminCreateUserRequest;
import com.hs.user.dto.request.UpdateRoleRequest;
import com.hs.user.service.RoleService;
import com.hs.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminPermissionSecurityTest {

    @Configuration
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {
        @Bean UserService users() { return mock(UserService.class); }
        @Bean RoleService roles() { return mock(RoleService.class); }
        @Bean UserAdminController userController(UserService s) { return new UserAdminController(s); }
        @Bean RoleAdminController roleController(RoleService s) { return new RoleAdminController(s); }
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "actor", "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static AdminCreateUserRequest createRequest() { return mock(AdminCreateUserRequest.class); }

    @Test void userHoldingAdminPermissionButNotAdminRoleIsDenied() {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            var c = ctx.getBean(UserAdminController.class);
            login("USER", Permissions.USER_CREATE, Permissions.USER_VIEW, Permissions.USER_UPDATE);
            assertThrows(AccessDeniedException.class, () -> c.createUser(createRequest()));
            assertThrows(AccessDeniedException.class, () -> c.findUserById("u"));
            assertThrows(AccessDeniedException.class, () -> c.disableUser("u"));
            verifyNoInteractions(ctx.getBean(UserService.class));
        }
    }

    @Test void adminWithoutThePermissionIsDeniedAndWithItPasses() {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            var c = ctx.getBean(UserAdminController.class);
            var users = ctx.getBean(UserService.class);
            login("ADMIN", Permissions.USER_VIEW);
            assertThrows(AccessDeniedException.class, () -> c.createUser(createRequest()));
            assertThrows(AccessDeniedException.class, () -> c.enableUser("u"));
            assertThrows(AccessDeniedException.class, () -> c.resendInvitation("u"));
            verifyNoInteractions(users);
            c.findUserById("u");
            verify(users).findUserById("u");
            login("ADMIN", Permissions.USER_CREATE);
            c.resendInvitation("u");
            verify(users).resendInvitation("u");
            login("ADMIN", Permissions.USER_UPDATE);
            c.disableUser("u");
            verify(users).updateUserStatus("u", false);
        }
    }

    @Test void methodLevelPermissionDoesNotInheritClassLevelViewPermission() {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            var c = ctx.getBean(RoleAdminController.class);
            var roles = ctx.getBean(RoleService.class);
            var update = new UpdateRoleRequest("d", null);
            login("ADMIN", Permissions.ROLE_VIEW);
            assertThrows(AccessDeniedException.class, () -> c.updateRole(update, "r"));
            login("ADMIN", Permissions.ROLE_UPDATE);
            assertThrows(AccessDeniedException.class, () -> c.findById("r"));
            c.updateRole(update, "r");
            verify(roles).updateRole("r", update);
        }
    }

    /** Guards audit H1: every admin handler must resolve to ADMIN plus one catalog permission. */
    @Test void everyAdminHandlerRequiresAdminRoleAndACatalogPermission() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> violations = new ArrayList<>();
        int handlers = 0;
        for (BeanDefinition def : scanner.findCandidateComponents("com.hs.api.controller.admin")) {
            Class<?> type = Class.forName(def.getBeanClassName());
            for (Method m : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())
                        || AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class) == null) {
                    continue;
                }
                handlers++;
                PreAuthorize pre = m.getAnnotation(PreAuthorize.class) != null
                        ? m.getAnnotation(PreAuthorize.class) : type.getAnnotation(PreAuthorize.class);
                String expr = pre == null ? "" : pre.value();
                boolean ok = expr.contains("hasAuthority('ADMIN')")
                        && Permissions.CATALOG.keySet().stream().anyMatch(p -> expr.contains("hasAuthority('" + p + "')"));
                if (!ok) violations.add(type.getSimpleName() + "." + m.getName() + " -> " + expr);
            }
        }
        assertTrue(handlers > 20, "scan found too few handlers: " + handlers);
        assertEquals(List.of(), violations);
    }
}
