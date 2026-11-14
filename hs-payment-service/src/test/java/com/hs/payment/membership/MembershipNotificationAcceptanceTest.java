package com.hs.payment.membership;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.membership.MembershipTier;
import com.hs.payment.dto.EthPaymentDtos;
import com.hs.payment.membership.notification.*;
import com.hs.payment.model.EthPaymentQuote;
import com.hs.payment.repository.*;
import com.hs.payment.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MembershipNotificationAcceptanceTest {
    final MembershipOrderRepository orders=mock(MembershipOrderRepository.class);
    final MembershipService memberships=mock(MembershipService.class);
    final MembershipNotificationOutboxRepository outbox=mock(MembershipNotificationOutboxRepository.class);
    final MembershipNotificationWriter writer=new MembershipNotificationWriter(outbox,"test.membership.events");
    final EthPaymentQuoteRepository quotes=mock(EthPaymentQuoteRepository.class);
    final EthPaymentChainClient chain=mock(EthPaymentChainClient.class);
    final MembershipCheckoutService sepay=new MembershipCheckoutService(orders,memberships,new SepaySigner("test-only"),quotes,chain,writer);
    final EthPaymentService eth=new EthPaymentService(orders,memberships,mock(EthWalletChallengeRepository.class),quotes,
        mock(EthVndPriceService.class),chain,mock(EthPaymentSignatures.class),writer);
    final String id="cb856b215fbf4d33b7f7fd54d5ce7867";
    final ObjectMapper json=new ObjectMapper();
    MembershipOrder order(MembershipOrder.Provider provider){
        var o=new MembershipOrder();o.setId(id);o.setOwnerId("persisted-owner");o.setTier(MembershipTier.STARTER);
        o.setProvider(provider);o.setStatus(MembershipOrder.Status.PENDING);o.setAmountVnd(49000);
        o.setCreatedAt(Instant.now());o.setExpiresAt(Instant.now().plusSeconds(900));
        when(orders.findById(id)).thenReturn(Optional.of(o));when(orders.lockById(id)).thenReturn(Optional.of(o));return o;
    }
    ObjectNode callback(String tx) {
        var root=json.createObjectNode().put("notification_type","ORDER_PAID");
        root.putObject("order").put("order_invoice_number",id).put("order_status","CAPTURED").put("order_currency","VND").put("order_amount","49000");
        root.putObject("transaction").put("transaction_id",tx).put("transaction_status","APPROVED").put("transaction_type","PAYMENT")
            .put("transaction_currency","VND").put("transaction_amount","49000");return root;
    }
    List<MembershipNotificationOutbox> rows(int count){var c=ArgumentCaptor.forClass(MembershipNotificationOutbox.class);verify(outbox,times(count)).save(c.capture());return c.getAllValues();}

    @Test void sepayActivationReplayAndSecondTransactionReviewExportExactContract() throws Exception {
        var o=order(MembershipOrder.Provider.SEPAY);when(memberships.activate("persisted-owner",MembershipTier.STARTER,false)).thenReturn(true);
        assertTrue(sepay.sepayIpn(callback("tx-one")));assertTrue(sepay.sepayIpn(callback("tx-one")));
        assertTrue(sepay.sepayIpn(callback("tx-two")));assertTrue(sepay.sepayIpn(callback("tx-two")));
        var rows=rows(2);verify(memberships,times(1)).activate(anyString(),any(),anyBoolean());
        assertEquals(MembershipOrder.Status.REVIEW,o.getStatus());assertNotNull(o.getPaidAt());assertEquals("tx-one",o.getTransactionNo());
        var fixtures=new ArrayList<Object>();
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);var e=json.readTree(row.getPayload());
            assertEquals(i==0?"MEMBERSHIP_ACTIVATED":"MEMBERSHIP_PAYMENT_REVIEW",e.get("eventType").asText());
            assertEquals("persisted-owner",e.get("recipients").get(0).asText());assertEquals(1,e.get("recipients").size());
            assertEquals(json.valueToTree(Map.of("orderId",id)),e.get("data"));assertEquals(id,row.getOrderId());
            assertTrue(e.get("occurredAt").asText().matches(".*\\.\\d{3}Z"));assertNull(row.getSentAt());
            assertFalse(row.getPayload().contains("49000"));assertFalse(row.getPayload().contains("tx-one"));assertFalse(row.getPayload().contains("SEPAY"));fixtures.add(e);
        }
        assertNotEquals(rows.getFirst().getEventId(),rows.getLast().getEventId());
        Path path=Path.of("target","membership-event-contracts.json");Files.createDirectories(path.getParent());Files.writeString(path,json.writeValueAsString(fixtures));
    }
    @ParameterizedTest @EnumSource(value=MembershipOrder.Status.class,names={"PENDING","CANCELLED","EXPIRED"})
    void verifiedLateOrCancelledPaymentNeedsReviewNotActivation(MembershipOrder.Status before){
        var o=order(MembershipOrder.Provider.SEPAY);o.setStatus(before);o.setExpiresAt(Instant.now().minusSeconds(1));
        assertTrue(sepay.sepayIpn(callback("late")));assertTrue(sepay.sepayIpn(callback("late")));
        assertEquals(MembershipOrder.Status.REVIEW,o.getStatus());assertTrue(rows(1).getFirst().getPayload().contains("MEMBERSHIP_PAYMENT_REVIEW"));
        verify(memberships,never()).activate(anyString(),any(),anyBoolean());
    }
    @Test void refusedActivationAndInvalidCallbacksNeverClaimActivated(){
        var o=order(MembershipOrder.Provider.SEPAY);var bad=callback("invalid");bad.withObject("transaction").put("transaction_amount","1");
        assertFalse(sepay.sepayIpn(bad));verifyNoInteractions(outbox);
        assertTrue(sepay.sepayIpn(callback("ok-review")));assertEquals(MembershipOrder.Status.REVIEW,o.getStatus());
        assertTrue(rows(1).getFirst().getPayload().contains("MEMBERSHIP_PAYMENT_REVIEW"));
    }
    @Test void ownershipFailedVerificationAndReplayForEthStayCorrect(){
        var o=order(MembershipOrder.Provider.ETH);var q=new EthPaymentQuote();q.setPaymentId("quote-id");q.setOrderId(id);q.setAmountVnd(BigDecimal.valueOf(49000));
        when(quotes.findById("quote-id")).thenReturn(Optional.of(q));when(quotes.findByIdForUpdate("quote-id")).thenReturn(Optional.of(q));
        String hash="0x"+"a".repeat(64);var req=new EthPaymentDtos.Settle("quote-id",hash);
        assertThrows(RuntimeException.class,()->eth.settle(id,"stranger",req));verifyNoInteractions(outbox);
        when(chain.verify(q,hash)).thenThrow(new IllegalStateException("not verified"));
        assertThrows(IllegalStateException.class,()->eth.settle(id,"persisted-owner",req));verifyNoInteractions(outbox);
        Instant mined=Instant.now();doReturn(mined).when(chain).verify(q,hash);
        when(memberships.activateAt("persisted-owner",MembershipTier.STARTER,false,mined)).thenReturn(true);
        eth.settle(id,"persisted-owner",req);eth.settle(id,"persisted-owner",req);
        assertEquals(MembershipOrder.Status.PAID,o.getStatus());assertTrue(rows(1).getFirst().getPayload().contains("MEMBERSHIP_ACTIVATED"));
        verify(memberships,times(1)).activateAt(anyString(),any(),anyBoolean(),any());
    }
    @Test void unsupportedPendingAndMissingIdentityCannotBeQueued(){
        var o=order(MembershipOrder.Provider.SEPAY);writer.enqueueTransition(o,MembershipOrder.Status.PENDING);verifyNoInteractions(outbox);
        o.setStatus(MembershipOrder.Status.PAID);o.setOwnerId(" ");assertThrows(IllegalStateException.class,()->writer.enqueueTransition(o,MembershipOrder.Status.PENDING));
        o.setOwnerId("owner");o.setId(null);assertThrows(IllegalStateException.class,()->writer.enqueueTransition(o,MembershipOrder.Status.PENDING));verifyNoInteractions(outbox);
    }
}
