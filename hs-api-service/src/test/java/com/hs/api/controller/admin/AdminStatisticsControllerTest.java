package com.hs.api.controller.admin;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.statistics.*;
import com.hs.contract.service.statistics.AdminStatisticsService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AdminStatisticsControllerTest {
    static final String URL="/api/v1/admin/statistics", SECRET="p5-test-only";
    @Configuration @EnableMethodSecurity(proxyTargetClass=true)
    static class Config {
        @Bean AdminStatisticsService service(){return mock(AdminStatisticsService.class);}
        @Bean BillingTime time(){var time=mock(BillingTime.class);when(time.today()).thenReturn(LocalDate.of(2026,11,28));return time;}
        @Bean AdminStatisticsController controller(AdminStatisticsService s,BillingTime t){return new AdminStatisticsController(s,t);}
    }
    AnnotationConfigApplicationContext context;
    AdminStatisticsService service;
    MockMvc mvc;
    @BeforeEach void setup(){
        context=new AnnotationConfigApplicationContext(Config.class); service=context.getBean(AdminStatisticsService.class);
        mvc=standaloneSetup(context.getBean(AdminStatisticsController.class)).setControllerAdvice(new GlobalException()).addFilters(new UserContextFilter(SECRET)).build();
        when(service.statistics(any())).thenReturn(new AdminStatisticsResponse(new AdminStatisticsResponse.Range("2026-10-30","2026-11-28","DAY","Asia/Ho_Chi_Minh"),Instant.EPOCH,Instant.EPOCH,Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of()));
    }
    @AfterEach void close(){context.close();}
    static MockHttpServletRequestBuilder auth(String role,String permissions){return get(URL).header("X-Internal-Secret",SECRET).header("X-User-Id","admin-test").header("X-User-Role",role).header("X-User-Authorities",permissions);}
    @Test void anonymousAndForgedHeadersAre401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header("X-User-Id","fake").header("X-User-Role","ADMIN").header("X-User-Authorities","STATISTICS_VIEW")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header("X-Internal-Secret","wrong").header("X-User-Id","fake").header("X-User-Role","ADMIN").header("X-User-Authorities","STATISTICS_VIEW")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void adminAndStatisticsAreBothRequired() throws Exception {
        mvc.perform(auth("USER","STATISTICS_VIEW,ADMIN")).andExpect(status().isForbidden());
        mvc.perform(auth("ADMIN","LISTING_VIEW")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void bothAliasesArePrivateAndQueryIsBounded() throws Exception {
        mvc.perform(auth("ADMIN","STATISTICS_VIEW")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.result.range.timezone").value("Asia/Ho_Chi_Minh"));
        mvc.perform(get("/admin/statistics").header("X-Internal-Secret",SECRET).header("X-User-Id","a").header("X-User-Role","ADMIN").header("X-User-Authorities","STATISTICS_VIEW")).andExpect(status().isOk());
        verify(service,times(2)).statistics(new AdminStatisticsQuery(LocalDate.of(2026,10,30),LocalDate.of(2026,11,28),AdminStatisticsQuery.Granularity.DAY));
    }
    @Test void invalidFiltersAndCallerIdentityAre400() throws Exception {
        for(var p:List.of(Map.of("from","2026-11-29"),Map.of("to","2026-11-29"),Map.of("from","2026-01-01"),
                Map.of("from","2023-01-01","granularity","MONTH"),Map.of("from","2026-02-30"),Map.of("granularity","WEEK"),Map.of("userId","other"))) {
            var request=auth("ADMIN","STATISTICS_VIEW");p.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        }
        mvc.perform(auth("ADMIN","STATISTICS_VIEW").param("from","2026-11-01","2026-11-02")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
