package com.hs.api.controller.user;

import com.hs.api.controller.admin.AdminKycController;
import com.hs.user.service.kyc.AdminKycService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AdminKycControllerTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean AdminKycService service(){return mock(AdminKycService.class);}
        @Bean AdminKycController controller(AdminKycService service){return new AdminKycController(service);}
    }

    @Test void quickVerificationRouteIsUnavailableAndStatusRemainsAdminOnly() throws Exception {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var controller=context.getBean(AdminKycController.class);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "actor","",List.of(new SimpleGrantedAuthority("USER"))));
            assertThrows(AccessDeniedException.class,()->controller.status("alice"));
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "actor","",List.of(new SimpleGrantedAuthority("USER"),new SimpleGrantedAuthority("KYC_VIEW"))));
            assertThrows(AccessDeniedException.class,()->controller.status("alice"));
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "actor","",List.of(new SimpleGrantedAuthority("ADMIN"))));
            assertThrows(AccessDeniedException.class,()->controller.status("alice"));
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "actor","",List.of(new SimpleGrantedAuthority("ADMIN"),new SimpleGrantedAuthority("KYC_VIEW"))));
            controller.status("alice");
            verify(context.getBean(AdminKycService.class)).status("alice");
            var mvc=MockMvcBuilders.standaloneSetup(controller).build();
            assertEquals(404,mvc.perform(post("/admin/users/alice/kyc/verify").contentType("application/json").content("{\"cccd\":\"001234567890\"}"))
                    .andReturn().getResponse().getStatus());
            verifyNoMoreInteractions(context.getBean(AdminKycService.class));
        } finally {SecurityContextHolder.clearContext();}
    }
}
