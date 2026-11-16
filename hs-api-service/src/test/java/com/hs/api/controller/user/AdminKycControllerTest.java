package com.hs.api.controller.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.api.controller.admin.AdminKycController;
import com.hs.user.dto.request.AdminVerifyKycRequest;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.model.constant.*;
import com.hs.user.service.kyc.AdminKycService;
import org.junit.jupiter.api.*;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AdminKycControllerTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean AdminKycService service(){return mock(AdminKycService.class);}
        @Bean AdminKycController controller(AdminKycService service){return new AdminKycController(service);}
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void login(String authority){SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("actor","",List.of(new SimpleGrantedAuthority(authority))));}

    @Test void userAuthorityCannotCallAdminVerificationOrReadStatus() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            login("USER");var controller=context.getBean(AdminKycController.class);
            assertThrows(AccessDeniedException.class,()->controller.verify("alice",new AdminVerifyKycRequest("001234567890")));
            assertThrows(AccessDeniedException.class,()->controller.status("alice"));
            verifyNoInteractions(context.getBean(AdminKycService.class));
        }
    }

    @Test void adminEndpointValidatesIdentifierAndReturnsTheExistingKycEnvelope() throws Exception {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            login("ADMIN");var service=context.getBean(AdminKycService.class);
            var response=new KycStatusResponse(KycStatus.VERIFIED,Instant.now(),KycProvider.ADMIN,"record",null,null);
            when(service.verify("alice","001234567890")).thenReturn(response);
            var mvc=MockMvcBuilders.standaloneSetup(context.getBean(AdminKycController.class)).build();
            assertEquals(400,mvc.perform(post("/admin/users/alice/kyc/verify").contentType("application/json").content("{\"cccd\":\"bad\"}")).andReturn().getResponse().getStatus());
            verifyNoInteractions(service);
            var result=mvc.perform(post("/admin/users/alice/kyc/verify").contentType("application/json").content("{\"cccd\":\"001234567890\",\"adminId\":\"forged\"}")).andReturn().getResponse();
            assertEquals(200,result.getStatus());
            var json=new ObjectMapper().readTree(result.getContentAsString());
            assertEquals("VERIFIED",json.path("result").path("status").asText());
            assertEquals("ADMIN",json.path("result").path("provider").asText());
            verify(service).verify("alice","001234567890");
        }
    }
}
