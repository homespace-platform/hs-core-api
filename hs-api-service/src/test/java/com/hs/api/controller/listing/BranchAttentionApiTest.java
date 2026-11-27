package com.hs.api.controller.listing;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.dto.branch.BranchAttentionResponse;
import com.hs.contract.dto.branch.BranchAttentionResponse.*;
import com.hs.contract.dto.branch.BranchOverviewResponse.*;
import com.hs.contract.service.BranchAttentionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BranchAttentionApiTest {
    private static final String DETAIL = "/branches/branch/attention";
    private static final String SUMMARY = "/branches/attention-summary";
    private static final String SECRET = "attention-test";
    private static final Instant B = Instant.parse("2026-11-30T17:00:00Z");
    private static final Instant R = Instant.parse("2026-11-20T12:00:00Z");
    private final BranchAttentionService service = mock();
    private final MockMvc mvc = standaloneSetup(new BranchAttentionController(service))
            .setControllerAdvice(new GlobalException()).addFilters(new UserContextFilter(SECRET)).build();

    @Test
    void getUsesVerifiedOwnerAndReturnsPrivateExactEnvelopeWithoutPostingPermission() throws Exception {
        var target = new Target(TargetKind.TRAY, "branch", "/dashboard/branches/branch?tab=map#unplaced");
        var work = new Work("UNPLACED_CONTRACT:c", WorkType.UNPLACED_CONTRACT, null, null, "HD-1", null,
                null, Clock.BILLING, Priority.NORMAL, target);
        when(service.attention("branch", "owner", 0, 20))
                .thenReturn(new BranchAttentionResponse("branch", B, R, List.of(work), 0, 20, 1));
        when(service.summary("owner")).thenReturn(new Summary(B, R, List.of(new BranchCount("branch", 1),
                new BranchCount("empty", 0))));
        mvc.perform(auth(DETAIL, "owner", "USER").param("ownerId", "other"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().json("""
                        {"code":1000,"result":{"branchId":"branch",
                          "billingNow":"2026-11-30T17:00:00Z","realNow":"2026-11-20T12:00:00Z",
                          "items":[{"key":"UNPLACED_CONTRACT:c","type":"UNPLACED_CONTRACT",
                            "roomId":null,"roomCode":null,"contextLabel":"HD-1","periodIndex":null,"dueAt":null,
                            "clock":"BILLING","priority":"NORMAL",
                            "target":{"kind":"TRAY","id":"branch","href":"/dashboard/branches/branch?tab=map#unplaced"}}],
                          "page":0,"size":20,"totalElements":1}}
                        """, JsonCompareMode.STRICT));
        mvc.perform(auth(SUMMARY, "owner", "USER").param("ownerId", "other"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().json("""
                        {"code":1000,"result":{"billingNow":"2026-11-30T17:00:00Z","realNow":"2026-11-20T12:00:00Z",
                          "branches":[{"branchId":"branch","count":1},{"branchId":"empty","count":0}]}}
                        """, JsonCompareMode.STRICT));
        verify(service).attention("branch", "owner", 0, 20);
        verify(service).summary("owner");
    }

    @ParameterizedTest
    @CsvSource({"page,''", "page,-1", "page,2147483648", "page,0.5", "size,''", "size,0", "size,101", "size,abc"})
    void invalidPageAndSizeAreBadRequests(String name, String value) throws Exception {
        assertBadQuery(auth(DETAIL, "owner", "USER").param(name, value));
    }

    @Test
    void repeatedParametersAreBadRequests() throws Exception {
        assertBadQuery(auth(DETAIL, "owner", "USER").param("page", "0", "1"));
        assertBadQuery(auth(DETAIL, "owner", "USER").param("size", "1", "1"));
    }

    @Test
    void maximumPageAndSizeReachService() throws Exception {
        when(service.attention("branch", "owner", Integer.MAX_VALUE, 100))
                .thenReturn(new BranchAttentionResponse("branch", B, R, List.of(), Integer.MAX_VALUE, 100, 1));
        mvc.perform(auth(DETAIL, "owner", "USER").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items").isEmpty())
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @Test
    void bothGetsRequireIdentityAndAdminCannotImpersonateOwner() throws Exception {
        for (String url : List.of(DETAIL, SUMMARY)) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1002));
        }
        verifyNoInteractions(service);
        when(service.attention("branch", "other", 0, 20))
                .thenThrow(new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND));
        mvc.perform(auth(DETAIL, "other", "ADMIN").param("ownerId", "owner"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    private void assertBadQuery(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequestBuilder auth(String url, String user, String role) {
        return get(url).header("X-Internal-Secret", SECRET).header("X-User-Id", user).header("X-User-Role", role);
    }
}
