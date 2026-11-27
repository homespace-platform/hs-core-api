package com.hs.api.controller.admin;

import com.hs.common.context.*;
import com.hs.payment.membership.MembershipOverpaymentAdminService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(MembershipOverpaymentAdminControllerTest.Security.class)
class MembershipOverpaymentAdminControllerTest {
    @Configuration @EnableMethodSecurity(proxyTargetClass = true) static class Security {
        @Bean MembershipOverpaymentAdminService service() { return mock(MembershipOverpaymentAdminService.class); }
        @Bean MembershipOverpaymentAdminController controller(MembershipOverpaymentAdminService s) { return new MembershipOverpaymentAdminController(s); }
    }
    @Autowired MembershipOverpaymentAdminController controller;
    @Autowired MembershipOverpaymentAdminService service;
    @AfterEach void clear() { UserContextHolder.clear(); SecurityContextHolder.clearContext(); reset(service); }

    void as(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u", "n",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test void adminWithPermissionMarksRefundedWithVerifiedActor() {
        as("ADMIN", "MEMBERSHIP_REFUND_MANAGE");
        UserContextHolder.set(new UserContext("admin-1", null));
        controller.markRefunded("p1", new MembershipOverpaymentAdminController.RefundedRequest("ck"));
        verify(service).markRefunded("p1", "admin-1", "ck");
    }

    @Test void usersAndAdminsWithoutPermissionAreDenied() {
        UserContextHolder.set(new UserContext("x", null));
        as("USER");
        assertThrows(AccessDeniedException.class, () -> controller.markRefunded("p1", null));
        as("ADMIN");
        assertThrows(AccessDeniedException.class, () -> controller.list(null, 1, 20));
        verifyNoInteractions(service);
    }
}
