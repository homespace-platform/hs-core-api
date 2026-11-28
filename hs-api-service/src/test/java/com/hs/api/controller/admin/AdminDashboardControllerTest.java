package com.hs.api.controller.admin;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.contract.dto.admin.AdminDashboardResponse;
import com.hs.contract.service.admin.AdminDashboardService;
import com.hs.user.service.kyc.AdminKycQueueService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AdminDashboardControllerTest {
    static final String SECRET="p4-test-only",OVERVIEW="/api/v1/admin/dashboard/overview",HEALTH="/api/v1/admin/system-health",KYC="/api/v1/admin/kyc";
    @Configuration @EnableMethodSecurity(proxyTargetClass=true)
    static class Config {
        @Bean AdminDashboardService service(){return mock(AdminDashboardService.class);}
        @Bean AdminKycQueueService kyc(){return mock(AdminKycQueueService.class);}
        @Bean AdminDashboardController overview(AdminDashboardService s){return new AdminDashboardController(s);}
        @Bean AdminSystemHealthController health(AdminDashboardService s){return new AdminSystemHealthController(s);}
        @Bean AdminKycQueueController queue(AdminKycQueueService s){return new AdminKycQueueController(s);}
    }
    AnnotationConfigApplicationContext context;AdminDashboardService service;AdminKycQueueService kyc;MockMvc mvc;
    @BeforeEach void setup(){context=new AnnotationConfigApplicationContext(Config.class);service=context.getBean(AdminDashboardService.class);kyc=context.getBean(AdminKycQueueService.class);
        when(service.overview(anyBoolean())).thenReturn(new AdminDashboardResponse(Instant.EPOCH,LocalDate.of(2026,11,28),Map.of(),Map.of(),Map.of(),null,null,Map.of(),List.of()));
        when(kyc.list(any())).thenReturn(new AdminKycQueueService.Page(List.of(),0,0,20));when(kyc.counts(any())).thenReturn(List.of());
        mvc=standaloneSetup(context.getBean(AdminDashboardController.class),context.getBean(AdminSystemHealthController.class),context.getBean(AdminKycQueueController.class)).setControllerAdvice(new GlobalException()).addFilters(new UserContextFilter(SECRET)).build();}
    @AfterEach void close(){context.close();}
    static MockHttpServletRequestBuilder auth(String url,String role,String permissions){return get(url).header("X-Internal-Secret",SECRET).header("X-User-Id","admin-test").header("X-User-Role",role).header("X-User-Authorities",permissions);}
    @Test void anonymousAndForgedIdentityAre401ForEveryEndpoint() throws Exception {for(String url:List.of(OVERVIEW,HEALTH,KYC,KYC+"/counts")){
        mvc.perform(get(url)).andExpect(status().isUnauthorized());mvc.perform(get(url).header("X-User-Id","fake").header("X-User-Role","ADMIN")).andExpect(status().isUnauthorized());}
        verifyNoInteractions(service,kyc);}
    @Test void userCannotForgeAdminAndKycRequiresItsOwnPermission() throws Exception {for(String url:List.of(OVERVIEW,HEALTH,KYC,KYC+"/counts"))mvc.perform(auth(url,"USER","ADMIN,KYC_VIEW,SYSTEM_HEALTH_VIEW")).andExpect(status().isForbidden());
        mvc.perform(auth(KYC,"ADMIN","USER_VIEW")).andExpect(status().isForbidden());mvc.perform(auth(KYC+"/counts","ADMIN","USER_VIEW")).andExpect(status().isForbidden());verifyNoInteractions(service,kyc);}
    @Test void adminMayViewOverviewWithoutSupplementaryPermissionsAndAliasesArePrivate() throws Exception {
        for(String url:List.of(OVERVIEW,"/admin/dashboard/overview"))mvc.perform(auth(url,"ADMIN","LISTING_VIEW")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(auth(HEALTH,"ADMIN","")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(auth(KYC,"ADMIN","KYC_VIEW")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(auth("/admin/kyc/counts","ADMIN","KYC_VIEW")).andExpect(status().isOk());}
    @Test void unrecognizedRepeatedAndInvalidFiltersAre400() throws Exception {
        for(String url:List.of(OVERVIEW,HEALTH)){mvc.perform(auth(url,"ADMIN","").param("userId","other")).andExpect(status().isBadRequest());mvc.perform(auth(url,"ADMIN","").param("fresh","true","false")).andExpect(status().isBadRequest());mvc.perform(auth(url,"ADMIN","").param("fresh","yes")).andExpect(status().isBadRequest());}
        mvc.perform(auth(KYC,"ADMIN","KYC_VIEW").param("size","51")).andExpect(status().isBadRequest());mvc.perform(auth(KYC,"ADMIN","KYC_VIEW").param("provider","unknown")).andExpect(status().isBadRequest());verifyNoInteractions(service,kyc);}
}
