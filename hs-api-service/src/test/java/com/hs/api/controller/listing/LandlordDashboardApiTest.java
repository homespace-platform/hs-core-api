package com.hs.api.controller.listing;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.contract.dto.dashboard.LandlordDashboardResponse;
import com.hs.contract.service.LandlordDashboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LandlordDashboardApiTest {
    private static final String URL = "/dashboard/landlord/summary";
    private static final String SECRET = "dashboard-test";
    private static final Instant B = Instant.parse("2026-11-30T17:00:00Z");
    private final LandlordDashboardService service = mock();
    private final MockMvc mvc = standaloneSetup(new LandlordDashboardController(service))
            .setControllerAdvice(new GlobalException()).addFilters(new UserContextFilter(SECRET)).build();

    private static LandlordDashboardResponse empty() {
        return new LandlordDashboardResponse(B, B, "2026-11", Map.of(), null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void ownerIsTheVerifiedCallerNeverARequestParameterAndTheReplyIsPrivate() throws Exception {
        when(service.summary("owner", null, null)).thenReturn(empty());

        mvc.perform(auth("owner", "USER").param("ownerId", "other").param("userId", "other"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.result.month").value("2026-11"))
                .andExpect(jsonPath("$.result.revenue").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.result.errors").isMap());

        verify(service).summary("owner", null, null);
        verifyNoMoreInteractions(service);
    }

    @Test
    void monthAndIncludeReachTheService() throws Exception {
        when(service.summary(eq("owner"), eq(YearMonth.of(2026, 10)),
                eq(new LinkedHashSet<>(List.of("setup", "rooms"))))).thenReturn(empty());

        mvc.perform(auth("owner", "ADMIN").param("month", "2026-10").param("include", "setup,rooms"))
                .andExpect(status().isOk());

        verify(service).summary("owner", YearMonth.of(2026, 10), new LinkedHashSet<>(List.of("setup", "rooms")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13", "2026-00", "0000-01", "2026-1", "202611", "", "2026-11-01"})
    void badMonthIsABadRequest(String month) throws Exception {
        mvc.perform(auth("owner", "USER").param("month", month)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "money,", ",money", "bogus", "money,unknown", "MONEY"})
    void badIncludeIsABadRequest(String include) throws Exception {
        mvc.perform(auth("owner", "USER").param("include", include)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(service);
    }

    @Test
    void repeatedParametersAreBadRequests() throws Exception {
        mvc.perform(auth("owner", "USER").param("month", "2026-10", "2026-11")).andExpect(status().isBadRequest());
        mvc.perform(auth("owner", "USER").param("include", "setup", "rooms")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void identityIsRequired() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1002));
        verify(service, never()).summary(any(), any(), any());
    }

    @Test
    void forgedHeadersWrongSecretAndUnknownRoleCannotReachTheDashboard() throws Exception {
        mvc.perform(get(URL).header("X-User-Id","owner").header("X-User-Role","ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header("X-User-Id","owner").header("X-User-Role","USER").header("X-Internal-Secret","wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(auth("owner","LANDLORD")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void anotherVerifiedCallerCannotChooseTheOwnersContextAndComplaintsAreSupported() throws Exception {
        when(service.summary("other",null,java.util.Set.of("complaints"))).thenReturn(empty());
        mvc.perform(auth("other","USER").param("ownerId","owner").param("branchId","owners-branch")
                .param("include","complaints")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("renterPhone"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("renterEmail"))));
        verify(service).summary("other",null,java.util.Set.of("complaints"));
        verifyNoMoreInteractions(service);
    }

    private static MockHttpServletRequestBuilder auth(String user, String role) {
        return get(URL).header("X-Internal-Secret", SECRET).header("X-User-Id", user).header("X-User-Role", role);
    }
}
