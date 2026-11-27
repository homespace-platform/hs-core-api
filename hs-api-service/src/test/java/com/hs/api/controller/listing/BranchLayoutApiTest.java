package com.hs.api.controller.listing;

import com.hs.api.advice.handler.GlobalException;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.security.Permissions;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.SaveBranchLayoutRequest;
import com.hs.listing.dto.response.BranchLayoutResponse;
import com.hs.listing.service.BranchLayoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BranchLayoutApiTest {
    private static final String PATH = "/branches/branch-a/layout";
    private static final String BODY = """
            {"expectedRevision":0,"floors":[{"label":"Floor","rows":[{"label":"Row","slots":[{"roomCode":"101"},{"roomCode":null}]}]}]}
            """;

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {
        @Bean BranchLayoutService service() { return mock(BranchLayoutService.class); }

        @Bean BranchLayoutController controller(BranchLayoutService service) {
            return new BranchLayoutController(service);
        }
    }

    private AnnotationConfigApplicationContext context;
    private BranchLayoutService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(BranchLayoutService.class);
        mvc = standaloneSetup(context.getBean(BranchLayoutController.class))
                .setControllerAdvice(new GlobalException()).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        UserContextHolder.clear();
        context.close();
    }

    @Test
    void missingIdentityGets401OnBothMethods() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1002));
        putLayout(BODY).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(service);
    }

    @Test
    void ownerWithoutPostingPermissionCanReadButCannotSave() throws Exception {
        login("owner-a", "USER");
        when(service.getLayout("branch-a", "owner-a")).thenReturn(new BranchLayoutResponse(0, List.of()));
        mvc.perform(get(PATH))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.revision").value(0))
                .andExpect(jsonPath("$.result.floors").isEmpty());
        putLayout(BODY).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1009));
        verify(service).getLayout("branch-a", "owner-a");
        verifyNoMoreInteractions(service);
    }

    @Test
    void putBindsTreeAndUsesVerifiedIdentity() throws Exception {
        login("owner-a", "USER", Permissions.LISTING_POST);
        var request = new SaveBranchLayoutRequest(0L, List.of(new SaveBranchLayoutRequest.Floor(null, "Floor",
                List.of(new SaveBranchLayoutRequest.Row(null, "Row", List.of(
                        new SaveBranchLayoutRequest.Slot(null, "101"), new SaveBranchLayoutRequest.Slot(null, null)))))));
        var row = new BranchLayoutResponse.Row("r1", "Row", List.of(
                new BranchLayoutResponse.Slot("s1", "101"), new BranchLayoutResponse.Slot("s2", null)));
        when(service.saveLayout("branch-a", "owner-a", request)).thenReturn(new BranchLayoutResponse(
                1, List.of(new BranchLayoutResponse.Floor("f1", "Floor", List.of(row)))));

        putLayout(BODY).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.revision").value(1))
                .andExpect(jsonPath("$.result.floors[0].id").value("f1"))
                .andExpect(jsonPath("$.result.floors[0].rows[0].slots[0].roomCode").value("101"))
                .andExpect(jsonPath("$.result.floors[0].rows[0].slots[1].id").value("s2"))
                .andExpect(jsonPath("$.result.floors[0].rows[0].slots[1].roomCode").doesNotExist());
        verify(service).saveLayout("branch-a", "owner-a", request);
    }

    @Test
    void passesThroughOwnerNotFoundAndStaleRevision() throws Exception {
        login("owner-b", "USER", Permissions.LISTING_POST);
        var notFound = new AppException(404, "Branch not found", HttpStatus.NOT_FOUND);
        when(service.getLayout("branch-a", "owner-b")).thenThrow(notFound);
        when(service.saveLayout(eq("branch-a"), eq("owner-b"), any())).thenThrow(notFound);
        mvc.perform(get(PATH)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        putLayout(BODY).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));

        login("owner-a", "USER", Permissions.LISTING_POST);
        when(service.saveLayout(eq("branch-a"), eq("owner-a"), any()))
                .thenThrow(new AppException(ListingErrorCode.BRANCH_LAYOUT_STALE));
        putLayout(BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(4074));
    }

    @Test
    void bindsAcknowledgedDetachAndKeepsItAbsentWhenOmitted() throws Exception {
        login("owner-a", "USER", Permissions.LISTING_POST);
        when(service.saveLayout(eq("branch-a"), eq("owner-a"), any())).thenReturn(new BranchLayoutResponse(1, List.of()));
        putLayout(BODY.replace("\"expectedRevision\":0,", "\"expectedRevision\":0,\"acknowledgedDetach\":"
                + "{\"listingIds\":[\"l1\"],\"contractIds\":[]},")).andExpect(status().isOk());
        putLayout(BODY).andExpect(status().isOk());
        var captor = org.mockito.ArgumentCaptor.forClass(SaveBranchLayoutRequest.class);
        verify(service, times(2)).saveLayout(eq("branch-a"), eq("owner-a"), captor.capture());
        assertEquals(new SaveBranchLayoutRequest.AcknowledgedDetach(List.of("l1"), List.of()),
                captor.getAllValues().get(0).acknowledgedDetach());
        assertNull(captor.getAllValues().get(1).acknowledgedDetach());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"listingIds\":[1],\"contractIds\":[]}", "{\"listingIds\":[true],\"contractIds\":[]}",
            "{\"listingIds\":[],\"contractIds\":[null,{}]}", "{\"listingIds\":\"l1\",\"contractIds\":[]}", "[]", "\"x\""})
    void rejectsWrongAcknowledgedDetachTypes(String ack) throws Exception {
        assertRejectsBody(BODY.replace("\"expectedRevision\":0,", "\"expectedRevision\":0,\"acknowledgedDetach\":" + ack + ","));
    }

    @Test
    void linksChangedIs409With4093() throws Exception {
        login("owner-a", "USER", Permissions.LISTING_POST);
        when(service.saveLayout(eq("branch-a"), eq("owner-a"), any()))
                .thenThrow(new AppException(ListingErrorCode.BRANCH_LAYOUT_LINKS_CHANGED));
        putLayout(BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(4093));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"expectedRevision\":0,\"floors\":{}}"})
    void malformedJsonAndNonArrayTreeReturn400(String body) throws Exception {
        assertRejectsBody(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"expectedRevision\":\"0\"", "\"expectedRevision\":0.9",
            "\"label\":42", "\"roomCode\":101", "\"roomCode\":true"
    })
    void rejectsWrongScalarTypesBeforeSaving(String field) throws Exception {
        String key = field.substring(0, field.indexOf(':'));
        String original = switch (key) {
            case "\"expectedRevision\"" -> "\"expectedRevision\":0";
            case "\"label\"" -> "\"label\":\"Floor\"";
            default -> "\"roomCode\":\"101\"";
        };
        assertRejectsBody(BODY.replace(original, field));
    }

    @Test
    void missingRoomCodeMustNotSilentlyTurnAnExistingRoomIntoAnAisle() throws Exception {
        assertRejectsBody("""
                {"expectedRevision":1,"floors":[{"id":"f1","label":"Floor","rows":[{"id":"r1","label":"Row","slots":[
                    {"id":"s1"},{"id":"s2","roomCode":"102"}
                ]}]}]}
                """);
    }

    private void assertRejectsBody(String body) throws Exception {
        login("owner-a", "USER", Permissions.LISTING_POST);
        putLayout(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(4073));
        verifyNoInteractions(service);
    }

    private ResultActions putLayout(String body) throws Exception {
        return mvc.perform(put(PATH).queryParam("ownerId", "owner-from-request")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void login(String owner, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                owner, "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
        UserContextHolder.set(new UserContext(owner, null));
    }
}
