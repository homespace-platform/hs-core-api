package com.hs.api.controller.listing;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.profile.RequiresCompleteProfile;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.security.Permissions;
import com.hs.contract.dto.branch.BranchAssignmentsResponse;
import com.hs.contract.dto.branch.BranchCandidatesResponse;
import com.hs.contract.dto.branch.BranchContractLinkResponse;
import com.hs.contract.service.BranchAssignmentService;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.constant.RoomManualStatus;
import com.hs.listing.service.BranchRoomLinkService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BranchAssignmentsApiTest {
    private static final String BRANCH = "/branches/b";
    private static final String CONTRACT = BRANCH + "/contracts/c";
    private static final String LISTING = BRANCH + "/rooms/r/listings/l";
    private static final String FLAG = BRANCH + "/rooms/r/manual-status";

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {
        @Bean BranchAssignmentService assignments() { return mock(BranchAssignmentService.class); }
        @Bean BranchRoomLinkService rooms() { return mock(BranchRoomLinkService.class); }
        @Bean BranchAssignmentsController controller(BranchAssignmentService assignments, BranchRoomLinkService rooms) {
            return new BranchAssignmentsController(assignments, rooms);
        }
    }

    private AnnotationConfigApplicationContext context;
    private MockMvc mvc;
    private BranchAssignmentService assignments;
    private BranchRoomLinkService rooms;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        assignments = context.getBean(BranchAssignmentService.class);
        rooms = context.getBean(BranchRoomLinkService.class);
        mvc = standaloneSetup(context.getBean(BranchAssignmentsController.class))
                .setControllerAdvice(new GlobalException()).build();
    }

    @AfterEach
    void close() {
        UserContextHolder.clear();
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void bothReadsRequireIdentityAndBindOwnerAndPagingWithoutPostingPermission() throws Exception {
        mvc.perform(get(BRANCH + "/assignments")).andExpect(status().isUnauthorized());
        mvc.perform(get(BRANCH + "/assignment-candidates").param("kind", "LISTING"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(assignments, rooms);

        login(false);
        when(assignments.assignments("b", "owner", 0, 20)).thenReturn(
                new BranchAssignmentsResponse(List.of(), new BranchAssignmentsResponse.Unplaced(List.of(), 0, 20, 0)));
        when(assignments.candidates("b", "owner", "CONTRACT", 2, 30)).thenReturn(
                new BranchCandidatesResponse(List.of(new BranchCandidatesResponse.Item("c", "HĐ c", "r", null)), 2, 30, 71));
        mvc.perform(get(BRANCH + "/assignments").param("ownerId", "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.rooms").isEmpty())
                .andExpect(jsonPath("$.result.unplaced.totalElements").value(0));
        mvc.perform(get(BRANCH + "/assignment-candidates").param("kind", "CONTRACT").param("page", "2").param("size", "30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items[0].roomId").value("r"))
                .andExpect(jsonPath("$.result.totalElements").value(71));
        verify(assignments).assignments("b", "owner", 0, 20);
        verify(assignments).candidates("b", "owner", "CONTRACT", 2, 30);
        verifyNoMoreInteractions(assignments, rooms);
    }

    @Test
    void listingsExposeNullableRoomCodeInAssignmentsAndCandidates() throws Exception {
        login(false);
        var listings = List.of(
                new BranchAssignmentsResponse.Listing("l1", "T1", ListingStatus.PUBLISHED, "P101"),
                new BranchAssignmentsResponse.Listing("l2", "T2", ListingStatus.PUBLISHED, null));
        when(assignments.assignments("b", "owner", 0, 20)).thenReturn(new BranchAssignmentsResponse(
                List.of(new BranchAssignmentsResponse.Room("r", RoomManualStatus.NONE, listings, List.of(), List.of())),
                new BranchAssignmentsResponse.Unplaced(List.of(), 0, 20, 0)));
        when(assignments.candidates("b", "owner", "LISTING", 0, 20)).thenReturn(new BranchCandidatesResponse(
                List.of(new BranchCandidatesResponse.Item("l1", "T1", null, "P101"),
                        new BranchCandidatesResponse.Item("l2", "T2", "r", null)), 0, 20, 2));
        mvc.perform(get(BRANCH + "/assignments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rooms[0].listings[0].roomCode").value("P101"))
                .andExpect(jsonPath("$.result.rooms[0].listings[1]", org.hamcrest.Matchers.hasKey("roomCode")));
        mvc.perform(get(BRANCH + "/assignment-candidates").param("kind", "LISTING")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[0].roomCode").value("P101"))
                .andExpect(jsonPath("$.result.items[1]", org.hamcrest.Matchers.hasKey("roomCode")));
    }

    @Test
    void allFiveWritesRequirePostingPermissionAndCompleteProfile() throws Exception {
        login(false);
        var writes = List.of(put(LISTING), delete(LISTING), body(CONTRACT, "{\"roomId\":null}"),
                body(CONTRACT, "{\"addToTray\":true}"),
                delete(CONTRACT), body(FLAG, "{\"status\":\"REPAIR\"}"));
        for (var write : writes) {
            mvc.perform(write).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1009));
        }
        for (String name : List.of("linkListing", "unlinkListing", "attachContract", "detachContract", "setManualStatus")) {
            var handler = java.util.Arrays.stream(BranchAssignmentsController.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
            assertTrue(handler.isAnnotationPresent(RequiresCompleteProfile.class), name);
        }
        verifyNoInteractions(assignments, rooms);
    }

    @Test
    void routesWriteUsingVerifiedIdentityAndNullMeansTray() throws Exception {
        login(true);
        when(assignments.attach("b", "owner", "c", null)).thenReturn(new BranchContractLinkResponse("c", "b", null));
        when(rooms.setManualStatus("b", "owner", "r", RoomManualStatus.REPAIR)).thenReturn(RoomManualStatus.REPAIR);
        mvc.perform(put(LISTING).param("ownerId", "other")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.listingId").value("l")).andExpect(jsonPath("$.result.roomId").value("r"));
        mvc.perform(delete(LISTING)).andExpect(status().isOk());
        mvc.perform(body(CONTRACT, "{\"roomId\":null,\"ownerId\":\"other\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.managementBranchId").value("b"));
        mvc.perform(delete(CONTRACT)).andExpect(status().isOk());
        mvc.perform(body(FLAG, "{\"status\":\"REPAIR\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.manualStatus").value("REPAIR"));
        verify(rooms).link("b", "owner", "r", "l");
        verify(rooms).unlink("b", "owner", "r", "l");
        verify(assignments).attach("b", "owner", "c", null);
        verify(assignments).detach("b", "owner", "c");
        verify(rooms).setManualStatus("b", "owner", "r", RoomManualStatus.REPAIR);
        verifyNoMoreInteractions(assignments, rooms);
    }

    @Test
    void trayAddUsesInsertOnlyActionWithoutARoomId() throws Exception {
        login(true);
        when(assignments.addToTray("b", "owner", "c")).thenReturn(new BranchContractLinkResponse("c", "b", "saved-room"));
        mvc.perform(body(CONTRACT, "{\"addToTray\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.roomId").value("saved-room"));
        verify(assignments).addToTray("b", "owner", "c");
        verifyNoMoreInteractions(assignments, rooms);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "{\"roomId\":3}", "{\"roomId\":\" \"}",
            "{\"addToTray\":true,\"roomId\":null}", "{\"addToTray\":\"true\"}"})
    void rejectsAbsentOrInvalidRoomIdWithoutCallingService(String json) throws Exception {
        login(true);
        mvc.perform(body(CONTRACT, json)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ListingErrorCode.BRANCH_ASSIGNMENT_INVALID.getCode()));
        verifyNoInteractions(assignments, rooms);
    }

    @Test
    void mapsBadFlagMissingOwnerResourceAndScheduleConflictToClientErrors() throws Exception {
        login(true);
        mvc.perform(body(FLAG, "{\"status\":\"OTHER\"}")).andExpect(status().isBadRequest());
        when(assignments.assignments("b", "owner", 0, 20))
                .thenThrow(new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND));
        doThrow(new AppException(ListingErrorCode.BRANCH_ROOM_STATUS_CONFLICT))
                .when(rooms).setManualStatus("b", "owner", "r", RoomManualStatus.EXTERNAL_RENT);
        mvc.perform(get(BRANCH + "/assignments")).andExpect(status().isNotFound());
        mvc.perform(body(FLAG, "{\"status\":\"EXTERNAL_RENT\"}")).andExpect(status().isConflict());
    }

    @Test
    void malformedPagingAndMissingKindReturnBadRequestBeforeCallingService() throws Exception {
        login(false);
        var paging = mvc.perform(get(BRANCH + "/assignments").param("page", "invalid")).andReturn();
        var missingKind = mvc.perform(get(BRANCH + "/assignment-candidates")).andReturn();
        assertAll(() -> assertEquals(400, paging.getResponse().getStatus()),
                () -> assertEquals(400, missingKind.getResponse().getStatus()));
        verifyNoInteractions(assignments, rooms);
    }

    private static MockHttpServletRequestBuilder body(String path, String json) {
        return put(path).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static void login(boolean canPost) {
        var authority = new SimpleGrantedAuthority(canPost ? Permissions.LISTING_POST : Permissions.CONTRACT_MANAGE);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("owner", "n/a", List.of(authority)));
        UserContextHolder.set(new UserContext("owner", null));
    }
}
