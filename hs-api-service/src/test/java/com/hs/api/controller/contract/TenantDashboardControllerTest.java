package com.hs.api.controller.contract;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.contract.dto.tenant.TenantDashboardResponse;
import com.hs.contract.dto.tenant.TenantDashboardResponse.*;
import com.hs.contract.service.TenantDashboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TenantDashboardControllerTest {
    private static final String URL = "/dashboard/tenant/summary";
    private static final String SECRET = "tenant-dashboard-test";
    private final TenantDashboardService service = mock();
    private final MockMvc mvc = standaloneSetup(new TenantDashboardController(service))
            .setControllerAdvice(new GlobalException()).addFilters(new UserContextFilter(SECRET)).build();

    private static TenantDashboardResponse empty() {
        Instant t = Instant.parse("2026-11-30T17:00:00Z");
        return new TenantDashboardResponse(t, t, "Asia/Ho_Chi_Minh", new Setup(false), new Leases(0, List.of()),
                new Payables(0, BigDecimal.ZERO, BigDecimal.ZERO, null, List.of(),
                        new Awaiting(0, BigDecimal.ZERO, List.of()), new RefundsIncoming(0, BigDecimal.ZERO, List.of())),
                new Tasks(0, 0, List.of()), List.of(), new Visits(0, List.of()),
                new Requests(Map.of(), List.of()), new Spending("VND", BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of(), 0),
                new Deposits(BigDecimal.ZERO, List.of()), new Waiting(0, List.of()),
                new Complaints(0, 0, List.of()), new Saved(0, 0, 0));
    }

    @Test
    void usesTheVerifiedCallerIgnoresNothingElseAndIsPrivate() throws Exception {
        when(service.summary("me", 12)).thenReturn(empty());
        when(service.summary("me", 6)).thenReturn(empty());
        var body = mvc.perform(auth("me").param("spendMonths", "6")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.result.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.result.payables.next").value((Object) null))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(auth("me")).andExpect(status().isOk());
        verify(service).summary("me", 6);
        verify(service).summary("me", 12);
        for (String leak : List.of("BankAccountSnapshot", "qrImageUrl", "phone", "email", "idNumber", "cccd", "reason\"")) {
            assertFalse(body.contains(leak), leak);
        }
    }

    @Test
    void anonymousIsUnauthorizedAndServiceIsNeverCalled() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(service);
    }

    @Test
    void forgedIdentityWithoutGatewaySecretAndUnknownRoleAreUnauthorized() throws Exception {
        mvc.perform(get(URL).header("X-User-Id", "other").header("X-User-Role", "USER"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header("X-User-Id", "me").header("X-User-Role", "USER")
                .header("X-Internal-Secret", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header("X-Internal-Secret", SECRET).header("X-User-Id", "me")
                .header("X-User-Role", "TENANT")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"userId,other", "role,ADMIN", "spendMonths,13", "spendMonths,0", "spendMonths,7", "spendMonths,''",
            "spendMonths,06", "spendMonths,abc", "spendMonths,-12"})
    void foreignOrInvalidParametersAreBadRequests(String name, String value) throws Exception {
        mvc.perform(auth("me").param(name, value)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(service);
    }

    @Test
    void repeatedParameterIsABadRequest() throws Exception {
        mvc.perform(auth("me").param("spendMonths", "6", "12")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequestBuilder auth(String user) {
        return get(URL).header("X-Internal-Secret", SECRET).header("X-User-Id", user).header("X-User-Role", "USER");
    }
}
