package com.hs.api.controller.admin;

import com.hs.contract.blockchain.api.ContractDocumentIntegrityService;
import com.hs.contract.blockchain.api.DocumentIntegrityResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContractDocumentAdminControllerTest {
    private static final byte[] BYTES = "%PDF-content".getBytes(StandardCharsets.UTF_8);
    private static final DocumentIntegrityResponse RESULT =
            new DocumentIntegrityResponse("pdf", "MATCH", "hash", "hash", Instant.now(), "Khớp");

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void adminRouteServesVerifiedPdfOnGetAndPostUnderBothPrefixes() throws Exception {
        var service = mock(ContractDocumentIntegrityService.class);
        when(service.contentForAdmin("pdf")).thenReturn(new ContractDocumentIntegrityService.VerifiedPdf(BYTES, "hop-dong.pdf", RESULT));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ContractDocumentAdminController(service)).build();
        for (String prefix : new String[]{"/admin", "/api/v1/admin"}) {
            mvc.perform(get(prefix + "/contracts/documents/pdf/content")).andExpect(status().isOk())
                    .andExpect(content().contentType("application/pdf")).andExpect(content().bytes(BYTES))
                    .andExpect(header().string("X-Document-Integrity", "MATCH"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(post("/admin/contracts/documents/pdf/content")).andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.homespace.verified-pdf")).andExpect(header().doesNotExist("Content-Disposition"));
        verify(service, never()).content(any());
    }

    @Configuration @EnableMethodSecurity(proxyTargetClass = true) static class Security {
        @Bean ContractDocumentIntegrityService service() { return mock(ContractDocumentIntegrityService.class); }
        @Bean ContractDocumentAdminController controller(ContractDocumentIntegrityService service) { return new ContractDocumentAdminController(service); }
    }

    @Test void needsAdminRoleAndContractViewPermission() {
        try (var context = new AnnotationConfigApplicationContext(Security.class)) {
            var secured = context.getBean(ContractDocumentAdminController.class);
            var target = context.getBean(ContractDocumentIntegrityService.class);
            when(target.contentForAdmin("pdf")).thenReturn(new ContractDocumentIntegrityService.VerifiedPdf(BYTES, "a.pdf", RESULT));
            var request = mock(HttpServletRequest.class);
            when(request.getMethod()).thenReturn("GET");
            for (String[] authorities : new String[][]{{"USER"}, {"ADMIN"}, {"CONTRACT_VIEW"}, {"ADMIN", "CONTRACT_TEMPLATE_VIEW"}}) {
                login(authorities);
                assertThrows(AccessDeniedException.class, () -> secured.content("pdf", false, request));
            }
            verifyNoInteractions(target);
            login("ADMIN", "CONTRACT_VIEW");
            secured.content("pdf", false, request);
            verify(target).contentForAdmin("pdf");
        }
    }

    private static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("u", "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
