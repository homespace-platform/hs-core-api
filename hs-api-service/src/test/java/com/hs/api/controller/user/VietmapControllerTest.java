package com.hs.api.controller.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.api.advice.handler.GlobalException;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.location.VietmapService;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.utils.CurrentUserUtils;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VietmapControllerTest {
    private final VietmapService service=mock(VietmapService.class);
    private final CurrentUserUtils users=mock(CurrentUserUtils.class);
    private final VietmapController controller=new VietmapController(service,users);
    private final VietmapService.Suggestion suggestion=new VietmapService.Suggestion(
            "auto:opaque+ref/with=characters", "197 Trần Phú, Phường Chợ Quán, Thành phố Hồ Chí Minh",
            "197 Trần Phú", "Phường Chợ Quán, Thành phố Hồ Chí Minh");

    @AfterEach void clearContext() { UserContextHolder.clear(); }

    private List<Runnable> calls() {
        return List.of(() -> controller.autocomplete("Trần Phú"), () -> controller.geocode("197 Trần Phú"),
                () -> controller.place(suggestion.refId()), () -> controller.reverse(BigDecimal.TEN,BigDecimal.valueOf(106)));
    }

    private void signIn() { UserContextHolder.set(new UserContext("customer-1","customer@example.test")); }

    private VietmapService.Place resolvedPlace() {
        return new VietmapService.Place(suggestion.refId(),suggestion.display(),suggestion.name(),"197","Trần Phú",
                suggestion.address(),"12","Thành phố Hồ Chí Minh",null,null,"18700","Phường Chợ Quán",
                new BigDecimal("10.7592229"),new BigDecimal("106.6759027"));
    }

    @Test void allFourEndpointsRequireASignedInUserBeforeSpendingProviderQuota() {
        for(UserContext context:java.util.Arrays.asList(null,new UserContext(null,null),new UserContext(" ",null))) {
            UserContextHolder.set(context);
            for(Runnable call:calls()) {
                var error=assertThrows(AppException.class,call::run);
                assertEquals(401,error.getStatusCode().value());assertEquals(ErrorCode.UNAUTHENTICATED.getCode(),error.getCode());
            }
        }
        verifyNoInteractions(users,service);
    }

    @Test void inactiveAccountsCannotReachAnyProviderEndpoint() {
        signIn();when(users.getCurrentUser()).thenThrow(new AppException(UserErrorCode.USER_DISABLED));
        for(Runnable call:calls()) assertThrows(AppException.class,call::run);
        verify(users,times(4)).getCurrentUser();verifyNoInteractions(service);
    }

    @Test void autocompleteAndGeocodeReturnSuggestionsInTheCommonEnvelope() {
        signIn();when(service.autocomplete("Trần Phú")).thenReturn(List.of(suggestion));
        when(service.geocode("197 Trần Phú")).thenReturn(List.of(suggestion));
        var auto=controller.autocomplete("Trần Phú");var geocode=controller.geocode("197 Trần Phú");
        assertEquals(1000,auto.getCode());assertEquals(List.of(suggestion),auto.getResult());
        assertEquals(1000,geocode.getCode());assertEquals(List.of(suggestion),geocode.getResult());
        verify(service).autocomplete("Trần Phú");verify(service).geocode("197 Trần Phú");
        verify(users,times(2)).getCurrentUser();verifyNoMoreInteractions(service);
    }

    @Test void placePreservesTheOpaqueReferenceAndReturnsCoordinates() {
        signIn();var place=resolvedPlace();
        when(service.place(suggestion.refId())).thenReturn(place);
        var response=controller.place(suggestion.refId());
        assertEquals(1000,response.getCode());assertSame(place,response.getResult());
        verify(service).place(suggestion.refId());verify(users).getCurrentUser();verifyNoMoreInteractions(service);
    }

    @Test void reversePassesCoordinatesWithoutSwappingOrRoundingAndAllowsNoMatches() {
        signIn();var lat=new BigDecimal("10.759222947000069");var lng=new BigDecimal("106.67590269100003");
        when(service.reverse(lat,lng)).thenReturn(List.of(suggestion));
        assertEquals(List.of(suggestion),controller.reverse(lat,lng).getResult());
        when(service.reverse(BigDecimal.ZERO,BigDecimal.ZERO)).thenReturn(List.of());
        assertEquals(List.of(),controller.reverse(BigDecimal.ZERO,BigDecimal.ZERO).getResult());
        verify(service).reverse(lat,lng);verify(service).reverse(BigDecimal.ZERO,BigDecimal.ZERO);
        verify(users,times(2)).getCurrentUser();verifyNoMoreInteractions(service);
    }

    @Test void providerFailureIsPropagatedWithoutAutomaticRetryOrFakeResults() {
        signIn();var error=new AppException(2455,"Provider unavailable",HttpStatus.BAD_GATEWAY);
        when(service.geocode("197 Trần Phú")).thenThrow(error);
        assertSame(error,assertThrows(AppException.class,() -> controller.geocode("197 Trần Phú")));
        verify(service).geocode("197 Trần Phú");verifyNoMoreInteractions(service);
    }

    @Test void httpRoutesBindTheDocumentedQueryNamesAndRejectMissingOrMalformedParameters() throws Exception {
        signIn();var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalException()).build();
        when(service.autocomplete("Trần Phú")).thenReturn(List.of(suggestion));
        when(service.geocode("197 Trần Phú")).thenReturn(List.of());
        when(service.reverse(new BigDecimal("10.7"),new BigDecimal("106.6"))).thenReturn(List.of());
        when(service.place(suggestion.refId())).thenReturn(resolvedPlace());
        var result=mvc.perform(get("/locations/autocomplete").param("text","Trần Phú"))
                .andExpect(status().isOk()).andReturn();
        var body=new ObjectMapper().readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1000,body.path("code").intValue());
        assertEquals(suggestion.refId(),body.path("result").get(0).path("refId").asText());
        mvc.perform(get("/locations/geocode").param("text","197 Trần Phú")).andExpect(status().isOk());
        var resolved=mvc.perform(get("/locations/place").param("refId",suggestion.refId())).andExpect(status().isOk()).andReturn();
        var resolvedBody=new ObjectMapper().readTree(resolved.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(new BigDecimal("10.7592229"),resolvedBody.path("result").path("latitude").decimalValue());
        assertTrue(resolvedBody.path("result").path("districtId").isNull());
        mvc.perform(get("/locations/reverse").param("latitude","10.7").param("longitude","106.6")).andExpect(status().isOk());
        verify(service).place(suggestion.refId());
        clearInvocations(service,users);
        for(String path:List.of("autocomplete","geocode","place","reverse"))
            mvc.perform(get("/locations/"+path)).andExpect(status().isBadRequest());
        mvc.perform(get("/locations/reverse").param("latitude","not-a-number").param("longitude","106"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service,users);
    }

    @Test void httpGuestReceivesUnauthorizedEnvelopeAndNeverCallsProvider() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalException()).build();
        var result=mvc.perform(get("/locations/autocomplete").param("text","Trần Phú"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertEquals(1002,new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("code").intValue());
        verifyNoInteractions(service,users);
    }

    @Test void httpProviderErrorsKeepTheirRealStatusAndDoNotBecomeSuccessResponses() throws Exception {
        signIn();var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalException()).build();
        for(var status:List.of(HttpStatus.SERVICE_UNAVAILABLE,HttpStatus.BAD_GATEWAY,HttpStatus.TOO_MANY_REQUESTS)) {
            doThrow(new AppException(2455,"Lookup unavailable",status)).when(service).geocode("197 Trần Phú");
            var response=mvc.perform(get("/locations/geocode").param("text","197 Trần Phú"))
                    .andExpect(status().is(status.value())).andReturn().getResponse();
            var body=new ObjectMapper().readTree(response.getContentAsString());
            assertEquals(2455,body.path("code").intValue());assertFalse(body.has("result"));
        }
        verify(service,times(3)).geocode("197 Trần Phú");verifyNoMoreInteractions(service);
    }
}
