package com.hs.api.controller.listing;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.dto.branch.BranchOverviewResponse;
import com.hs.contract.dto.branch.BranchOverviewResponse.*;
import com.hs.contract.service.BranchOverviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.json.JsonCompareMode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BranchOverviewApiTest {
    private static final String URL = "/branches/branch/overview";
    private static final String TEST_SECRET = "branch-overview-test-secret";
    private static final YearMonth DEC = YearMonth.of(2026, 12);
    private static final Instant BILLING = Instant.parse("2026-11-30T17:00:00Z");
    private static final Instant REAL = Instant.parse("2026-11-20T12:00:00Z");

    private final BranchOverviewService service = mock(BranchOverviewService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = standaloneSetup(new BranchOverviewController(service))
            .setControllerAdvice(new GlobalException()).addFilters(new UserContextFilter(TEST_SECRET)).build();

    @Test
    void ownerWithoutPostingPermissionGetsExactJsonAndPrivateNoStore() throws Exception {
        var contract = new Target(TargetKind.CONTRACT, "c", "/dashboard/landlord/contracts/c?tab=overview");
        var invoice = new Target(TargetKind.INVOICE, "first-rent:p", "/dashboard/landlord/contracts/c?tab=payment&section=monthly&invoice=first-rent%3Ap");
        var rental = new Target(TargetKind.RENTAL_REQUEST, "rental", "/dashboard/rental-requests?requestId=rental");
        var receipt = new Payment("first-rent:p", PaymentKind.INITIAL, "c", PaymentStatus.PENDING_CONFIRMATION,
                BILLING, BILLING.plusSeconds(60), Clock.BILLING, invoice);
        var unplaced = new Payment("first-rent:q", PaymentKind.INITIAL, null, PaymentStatus.OVERDUE,
                BILLING, null, Clock.BILLING, rental);
        var meter = new Meter("c", 0, MeterStatus.UPCOMING, LocalDate.of(2026, 12, 4),
                Instant.parse("2026-12-04T17:00:00Z"),
                new Target(TargetKind.CONTRACT, "c", "/dashboard/landlord/contracts/c?tab=payment&section=monthly"));
        var expiry = new Expiry("c", ExpiryStatus.WITHIN_60, LocalDate.of(2027, 1, 5), 35,
                new Target(TargetKind.CONTRACT, "c", "/dashboard/landlord/contracts/c?tab=end"));
        var occupied = new Room("r1", RoomStatus.OCCUPIED, "Tenant", contract,
                new Stay(null, LocalDate.of(2026, 12, 12), rental), new PaymentLayer(receipt.status(), 1, List.of(receipt)),
                List.of(meter), List.of(expiry), null);
        var vacant = new Room("r2", RoomStatus.VACANT, null,
                new Target(TargetKind.LISTING, "l2", "/dashboard/properties/view?id=l2"), null,
                new PaymentLayer(PaymentStatus.NONE, 0, List.of()), List.of(), List.of(), "HIDDEN");
        var unlinked = new Room("r3", RoomStatus.UNLINKED, null, null, null, new PaymentLayer(PaymentStatus.NONE, 0, List.of()));
        when(service.overview("branch", "owner", DEC)).thenReturn(
                new BranchOverviewResponse("branch", "2026-12", BILLING, REAL, List.of(occupied, vacant, unlinked), List.of(unplaced)));

        mvc.perform(authenticated("owner", "USER").param("month", "2026-12").param("ownerId", "other"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().json("""
                        {"code":1000,"result":{"branchId":"branch","month":"2026-12",
                          "billingNow":"2026-11-30T17:00:00Z","realNow":"2026-11-20T12:00:00Z",
                          "rooms":[
                            {"roomId":"r1","status":"OCCUPIED","tenantName":"Tenant",
                             "target":{"kind":"CONTRACT","id":"c","href":"/dashboard/landlord/contracts/c?tab=overview"},
                             "nextStay":{"tenantName":null,"moveInDate":"2026-12-12",
                               "target":{"kind":"RENTAL_REQUEST","id":"rental","href":"/dashboard/rental-requests?requestId=rental"}},
                             "payments":{"status":"PENDING_CONFIRMATION","count":1,"items":[
                               {"id":"first-rent:p","kind":"INITIAL","contractId":"c","status":"PENDING_CONFIRMATION",
                                "dueAt":"2026-11-30T17:00:00Z","confirmationDueAt":"2026-11-30T17:01:00Z","clock":"BILLING",
                                "target":{"kind":"INVOICE","id":"first-rent:p","href":"/dashboard/landlord/contracts/c?tab=payment&section=monthly&invoice=first-rent%3Ap"}}]},
                             "meters":[{"contractId":"c","periodIndex":0,"status":"UPCOMING","meterDate":"2026-12-04",
                               "deadlineAt":"2026-12-04T17:00:00Z",
                               "target":{"kind":"CONTRACT","id":"c","href":"/dashboard/landlord/contracts/c?tab=payment&section=monthly"}}],
                             "contracts":[{"contractId":"c","status":"WITHIN_60","endDateExclusive":"2027-01-05","daysRemaining":35,
                               "target":{"kind":"CONTRACT","id":"c","href":"/dashboard/landlord/contracts/c?tab=end"}}],
                             "listingStatus":null},
                            {"roomId":"r2","status":"VACANT","tenantName":null,
                             "target":{"kind":"LISTING","id":"l2","href":"/dashboard/properties/view?id=l2"},"nextStay":null,
                             "payments":{"status":"NONE","count":0,"items":[]},"meters":[],"contracts":[],
                             "listingStatus":"HIDDEN"},
                            {"roomId":"r3","status":"UNLINKED","tenantName":null,"target":null,"nextStay":null,
                             "payments":{"status":"NONE","count":0,"items":[]},"meters":[],"contracts":[],
                             "listingStatus":null}],
                          "unplacedPayments":[{"id":"first-rent:q","kind":"INITIAL","contractId":null,"status":"OVERDUE",
                            "dueAt":"2026-11-30T17:00:00Z","confirmationDueAt":null,"clock":"BILLING",
                            "target":{"kind":"RENTAL_REQUEST","id":"rental","href":"/dashboard/rental-requests?requestId=rental"}}]}}
                        """, JsonCompareMode.STRICT));
        verify(service).overview("branch", "owner", DEC);
    }

    @Test
    void absentMonthLetsServiceChooseAndEmptyLayersAreArrays() throws Exception {
        when(service.overview("branch", "owner", null)).thenReturn(
                new BranchOverviewResponse("branch", "2026-12", BILLING, REAL, List.of(), List.of()));
        mvc.perform(authenticated("owner", "USER").param("unrelated", "anything"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.month").value("2026-12"))
                .andExpect(jsonPath("$.result.rooms").isEmpty()).andExpect(jsonPath("$.result.unplacedPayments").isEmpty());
        verify(service).overview("branch", "owner", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0000-12", "2026-00", "2026-13", "2026-1", " 2026-12", "2026-12-01", "10000-01"})
    void invalidMonthReturns400(String month) throws Exception {
        mvc.perform(authenticated("owner", "USER").param("month", month))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(service);
    }

    @Test
    void repeatedMonthReturns400() throws Exception {
        mvc.perform(authenticated("owner", "USER").param("month", "2026-12", "2026-12"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(service);
    }

    @Test
    void missingVerifiedIdentityReturns401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(service);
    }

    @Test
    void anotherOwnerWithAdminRoleStillGetsBranch404() throws Exception {
        when(service.overview("branch", "other", null)).thenThrow(new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND));
        mvc.perform(authenticated("other", "ADMIN").param("ownerId", "owner"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Không tìm thấy chi nhánh"))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service).overview("branch", "other", null);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(String id, String role) {
        return get(URL).header("X-Internal-Secret", TEST_SECRET).header("X-User-Id", id).header("X-User-Role", role);
    }
}
