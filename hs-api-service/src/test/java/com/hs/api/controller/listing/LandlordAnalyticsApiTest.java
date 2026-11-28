package com.hs.api.controller.listing;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.dto.analytics.LandlordAnalyticsQuery;
import com.hs.contract.dto.analytics.LandlordAnalyticsResponse;
import com.hs.contract.dto.analytics.LandlordAnalyticsResponse.*;
import com.hs.contract.service.analytics.LandlordAnalyticsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.math.BigDecimal;
import com.hs.contract.service.analytics.LandlordAnalyticsExportService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LandlordAnalyticsApiTest {
    private static final String URL = "/dashboard/analytics";
    private static final String SECRET = "landlord-analytics-test-secret";

    private final LandlordAnalyticsService service = mock(LandlordAnalyticsService.class);
    private final LandlordAnalyticsExportService exports = mock(LandlordAnalyticsExportService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = standaloneSetup(new LandlordAnalyticsController(service, exports))
            .setControllerAdvice(new GlobalException()).addFilters(new UserContextFilter(SECRET)).build();

    @Test
    void ownerIsTheVerifiedCallerAndResponseIsPrivateNoStore() throws Exception {
        var response = new LandlordAnalyticsResponse(Instant.parse("2026-11-30T17:00:00Z"), "2025-12", "2026-11",
                LocalDate.of(2026, 9, 2),
                List.of(new RevenueMonth("2026-11", bd(52_300_000), bd(0), bd(52_300_000), bd(68_000_000), bd(49_000_000),
                        bd(3_300_000), bd(0), bd(0), bd(0))),
                List.of(new OccupancyMonth("2026-11", 24, 652, 720, 0.906)),
                new Receivables(Instant.parse("2026-11-30T17:00:00Z"), bd(100), 1,
                        List.of(new Bucket("D0_7", 1, bd(100))), List.of()),
                new Listings(null, 1, 10, 0, List.of(), List.of()),
                new Funnel(new Views(1204, true, null), 86, 31, 12));
        when(service.analytics(eq("owner"), any())).thenReturn(response);

        mvc.perform(authenticated("owner", "USER").param("from", "2025-12").param("to", "2026-11")
                        .param("branchId", "b1").param("listingId", "l1").param("include", "revenue,funnel")
                        .param("listingPage", "2").param("listingSize", "20").param("noLeadsOnly", "true")
                        .param("ownerId", "other").param("userId", "other"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.revenue[0].receivedVnd").value(52_300_000))
                .andExpect(jsonPath("$.result.occupancy[0].rate").value(0.906))
                .andExpect(jsonPath("$.result.funnel.views.cumulative").value(true))
                .andExpect(jsonPath("$.result.receiptsSince").value("2026-09-02"));

        var captor = ArgumentCaptor.forClass(LandlordAnalyticsQuery.class);
        verify(service).analytics(eq("owner"), captor.capture());
        var q = captor.getValue();
        assertEquals(YearMonth.of(2025, 12), q.from());
        assertEquals(YearMonth.of(2026, 11), q.to());
        assertEquals("b1", q.branchId());
        assertEquals("l1", q.listingId());
        assertEquals(Set.of("revenue", "funnel"), q.include());
        assertEquals(2, q.listingPage());
        assertEquals(20, q.listingSize());
        assertTrue(q.noLeadsOnly());
        verify(service, never()).analytics(eq("other"), any());
    }

    @Test
    void noParametersMeansServiceDefaults() throws Exception {
        when(service.analytics(eq("owner"), any())).thenReturn(
                new LandlordAnalyticsResponse(Instant.EPOCH, "2025-12", "2026-11", null, List.of(), List.of(), null, null, null));
        mvc.perform(authenticated("owner", "USER")).andExpect(status().isOk());
        var captor = ArgumentCaptor.forClass(LandlordAnalyticsQuery.class);
        verify(service).analytics(eq("owner"), captor.capture());
        var q = captor.getValue();
        assertEquals(null, q.from());
        assertEquals(null, q.to());
        assertTrue(q.include().isEmpty());
        assertEquals(0, q.listingSize());
    }

    @ParameterizedTest
    @CsvSource({"from,2026-13", "from,2026-1", "to,0000-01", "to,''", "from,' 2026-12'", "include,'revenue,bogus'",
            "include,revenue;funnel", "include,',revenue'", "include,'revenue,revenue'", "listingSize,abc",
            "listingPage,-1", "noLeadsOnly,yes", "branchId,''", "listingId,'a b'", "branchId,../x"})
    void invalidParametersReturn400(String name, String value) throws Exception {
        mvc.perform(authenticated("owner", "USER").param(name, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(service);
    }

    @Test
    void repeatedParameterReturns400() throws Exception {
        mvc.perform(authenticated("owner", "USER").param("from", "2026-01", "2026-02"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(authenticated("owner", "USER").param("branchId", "a", "b"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void missingVerifiedIdentityReturns401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(service);
    }

    @Test
    void foreignBranchOrListingIsNotFoundEvenForAnAdminRole() throws Exception {
        when(service.analytics(eq("other"), any()))
                .thenThrow(new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND));
        mvc.perform(authenticated("other", "ADMIN").param("branchId", "owners-branch"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void semanticRangeErrorsFromTheServiceStayBadRequest() throws Exception {
        when(service.analytics(eq("owner"), any()))
                .thenThrow(new AppException(com.hs.common.advice.entity.enums.ErrorCode.INVALID_REQUEST));
        mvc.perform(authenticated("owner", "USER").param("from", "2026-11").param("to", "2026-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void exportsUseVerifiedIdentityRejectUntrustedHeadersAndKeepAttachmentPrivate() throws Exception {
        mvc.perform(get(URL + "/export").param("report", "revenue").param("format", "csv")
                .header("X-User-Id", "owner").header("X-User-Role", "USER")).andExpect(status().isUnauthorized());
        when(exports.export(eq("owner"), any(), eq("revenue"), eq("csv"), eq("receipt"))).thenReturn(
                new LandlordAnalyticsExportService.Export("homespace-revenue-2026-11_2026-11.csv", "text/csv;charset=UTF-8", new byte[]{1,2}));
        mvc.perform(get(URL + "/export").header("X-Internal-Secret", SECRET).header("X-User-Id", "owner").header("X-User-Role", "USER")
                .param("report", "revenue").param("format", "csv").param("ownerId", "other"))
                .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"homespace-revenue-2026-11_2026-11.csv\""));
        verify(exports).export(eq("owner"), any(), eq("revenue"), eq("csv"), eq("receipt"));
        mvc.perform(get(URL + "/export").header("X-Internal-Secret", SECRET).header("X-User-Id", "owner").header("X-User-Role", "USER")
                .param("report", "revenue", "funnel").param("format", "csv")).andExpect(status().isBadRequest());
    }

    private static BigDecimal bd(long value) { return BigDecimal.valueOf(value); }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(String id, String role) {
        return get(URL).header("X-Internal-Secret", SECRET).header("X-User-Id", id).header("X-User-Role", role);
    }
}
