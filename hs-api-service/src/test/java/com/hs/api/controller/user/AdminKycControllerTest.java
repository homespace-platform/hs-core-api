package com.hs.api.controller.user;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.controller.admin.AdminKycController;
import com.hs.user.dto.request.AdminKycRequest;
import com.hs.user.dto.response.AdminKycStatusResponse;
import com.hs.user.service.kyc.AdminKycService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminKycControllerTest {
    static final String BODY = "{\"cccd\":\"001234567890\",\"fullName\":\"NGUYEN VAN A\",\"birthDate\":\"2000-01-01\",\"gender\":\"Nam\","
            + "\"permanentAddress\":\"Ha Noi\",\"issueDate\":\"2020-01-01\",\"expirationDate\":\"2040-01-01\",\"issuePlace\":\"Cuc\","
            + "\"faceMatchPercent\":1,\"faceValid\":false}";

    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean AdminKycService service() { return mock(AdminKycService.class); }
        @Bean AdminKycController controller(AdminKycService service) { return new AdminKycController(service); }
    }

    private static void login(String... a) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "actor", "", java.util.Arrays.stream(a).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test void putIsAdminOnlyGetNeedsKycViewAndResponsesCarryNoPersonalData() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(AdminKycController.class);
            var service = context.getBean(AdminKycService.class);
            var req = new AdminKycRequest("1", "n", "d", "g", "a", "i", "e", "p");
            login("USER", "KYC_VIEW");
            assertThrows(AccessDeniedException.class, () -> controller.set("alice", req));
            assertThrows(AccessDeniedException.class, () -> controller.status("alice"));
            login("ADMIN");
            assertThrows(AccessDeniedException.class, () -> controller.status("alice"));
            assertThrows(AccessDeniedException.class, () -> controller.set("alice", req));
            verifyNoInteractions(service);
            login("ADMIN", "KYC_MANAGE");
            var out = new AdminKycStatusResponse(true, "ADMIN", "Quản trị viên", Instant.parse("2026-11-27T00:00:00Z"));
            when(service.set(eq("alice"), any())).thenReturn(out);
            when(service.status("alice")).thenReturn(out);
            var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalException()).build();
            var put = mvc.perform(put("/admin/users/alice/kyc").contentType("application/json").content(BODY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result.verified").value(true))
                    .andExpect(jsonPath("$.result.provider").value("ADMIN"))
                    .andExpect(jsonPath("$.result.providerLabel").value("Quản trị viên"))
                    .andReturn().getResponse().getContentAsString();
            login("ADMIN", "KYC_VIEW", "KYC_MANAGE");
            var get = mvc.perform(get("/admin/users/alice/kyc")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            for (String body : List.of(put, get))
                for (String pii : List.of("cccd", "fullName", "001234567890", "NGUYEN", "birthDate", "permanentAddress", "gender", "issuePlace", "faceMatch"))
                    assertFalse(body.contains(pii), pii);
            // extra score/flag fields in the body are ignored: the service only receives identity fields
            var captured = ArgumentCaptor.forClass(AdminKycRequest.class);
            verify(service).set(eq("alice"), captured.capture());
            assertEquals("001234567890", captured.getValue().cccd());
        } finally { SecurityContextHolder.clearContext(); }
    }
}
