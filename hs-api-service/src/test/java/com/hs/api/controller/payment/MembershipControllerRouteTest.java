package com.hs.api.controller.payment;

import com.hs.listing.membership.MembershipService;
import com.hs.payment.membership.MembershipCheckoutService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MembershipControllerRouteTest {
    @Test
    void plansAreAvailableAtThePathReceivedFromGateway() throws Exception {
        var memberships = mock(MembershipService.class);
        when(memberships.plans()).thenReturn(List.of());
        var mvc = standaloneSetup(new MembershipController(memberships, mock(MembershipCheckoutService.class))).build();

        mvc.perform(get("/membership/plans")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/membership/plans")).andExpect(status().isOk());
    }

    @Test
    void sepayIpnRequiresConfiguredSecretBeforeAcknowledging() throws Exception {
        var checkout = mock(MembershipCheckoutService.class);
        var mvc = standaloneSetup(new MembershipController(mock(MembershipService.class), checkout)).build();
        var request = post("/membership/sepay/ipn").contentType(MediaType.APPLICATION_JSON)
                .content("{\"notification_type\":\"ORDER_PAID\"}");

        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        when(checkout.sepayAuthenticated("test-secret")).thenReturn(true);
        mvc.perform(post("/membership/sepay/ipn").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Secret-Key", "test-secret").content("{\"notification_type\":\"ORDER_PAID\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        when(checkout.sepayIpn(any(JsonNode.class))).thenReturn(true);
        mvc.perform(post("/api/v1/membership/sepay/ipn").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Secret-Key", "test-secret").content("{\"notification_type\":\"ORDER_PAID\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }
}
