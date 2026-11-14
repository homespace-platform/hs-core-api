package com.hs.payment.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.payment.model.PaymentRequest;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentNotificationWriterTest {
    @Test void exportsObligationOverdueEnvelope() throws Exception {
        writer.enqueue(payment,PaymentNotificationWriter.Event.PAYMENT_OBLIGATION_OVERDUE,payment.getPayerId());
        var captured=org.mockito.ArgumentCaptor.forClass(PaymentNotificationOutbox.class);verify(outbox).save(captured.capture());
        var event=json.readTree(captured.getValue().getPayload());
        assertEquals("PAYMENT_OBLIGATION_OVERDUE",event.get("eventType").asText());assertEquals(1,event.get("recipients").size());
        assertEquals(payment.getPayerId(),event.get("recipients").get(0).asText());assertEquals(1,event.get("data").size());
        assertEquals(payment.getId(),event.get("data").get("paymentId").asText());assertEquals(6,event.size());
        String output=System.getenv("HS_OBLIGATION_OVERDUE_FIXTURE_OUT");
        if(output!=null&&!output.isBlank())Files.write(Path.of(output),List.of(captured.getValue().getPayload()));
    }
    final PaymentNotificationOutboxRepository outbox=mock(PaymentNotificationOutboxRepository.class);
    final PaymentNotificationWriter writer=new PaymentNotificationWriter(outbox,"test.payment.notifications");
    final PaymentRequest payment=PaymentRequest.builder().id("cb856b21-5fbf-4d33-b7f7-fd54d5ce7867")
        .payerId("stored-payer").payeeId("stored-payee").rejectedReason("PRIVATE_REASON").build();
    final ObjectMapper json=new ObjectMapper();

    @Test void everyTypeProducesMinimalVersionedEnvelopeAndDistinctImmutableIdentity() throws Exception {
        for(var type:PaymentNotificationWriter.Event.values())writer.enqueue(payment,type,payment.getPayerId());
        var capture=org.mockito.ArgumentCaptor.forClass(PaymentNotificationOutbox.class);
        verify(outbox,times(PaymentNotificationWriter.Event.values().length)).save(capture.capture());
        var ids=new HashSet<String>();var payloads=new ArrayList<String>();
        for(var row:capture.getAllValues()){
            var e=json.readTree(row.getPayload());
            assertEquals(Set.of("eventId","eventType","version","occurredAt","recipients","data"),fields(e));
            assertEquals(1,e.get("version").asInt());assertEquals(row.getEventId(),e.get("eventId").asText());
            assertTrue(ids.add(UUID.fromString(row.getEventId()).toString()));
            assertTrue(e.get("occurredAt").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
            assertEquals(Set.of("paymentId"),fields(e.get("data")));assertEquals(payment.getId(),e.get("data").get("paymentId").asText());
            assertEquals(1,e.get("recipients").size());assertEquals("stored-payer",e.get("recipients").get(0).asText());
            assertEquals(payment.getId(),row.getPaymentId());assertEquals("test.payment.notifications",row.getTopic());
            assertNull(row.getSentAt());assertNotNull(row.getCreatedAt());assertFalse(row.getPayload().contains("PRIVATE_REASON"));
            payloads.add(row.getPayload());
        }
        String fixture=System.getProperty("payment.notification.fixture");
        if(fixture!=null)Files.write(Path.of(fixture),payloads);
    }
    static Set<String> fields(com.fasterxml.jackson.databind.JsonNode node){
        var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);return fields;
    }
    @Test void partyActionReachesOnlyOtherPartyAndAdminDecisionUsesSeparateSingletonEnvelopes() throws Exception {
        var type=PaymentNotificationWriter.Event.PAYMENT_DISPUTE_REOPENED;
        writer.enqueueOthers(payment,type,"stored-payer");writer.enqueueOthers(payment,type,"outside-admin");
        var capture=org.mockito.ArgumentCaptor.forClass(PaymentNotificationOutbox.class);verify(outbox,times(3)).save(capture.capture());
        var recipients=new ArrayList<String>();for(var row:capture.getAllValues())recipients.add(json.readTree(row.getPayload()).get("recipients").get(0).asText());
        assertEquals(List.of("stored-payee","stored-payer","stored-payee"),recipients);
        assertEquals(3,capture.getAllValues().stream().map(PaymentNotificationOutbox::getEventId).distinct().count());
    }
    @Test void invalidRecipientOrMissingPaymentIdentityCannotEnqueue(){
        var type=PaymentNotificationWriter.Event.PAYMENT_RECEIPT_CONFIRMED;
        assertThrows(IllegalStateException.class,()->writer.enqueue(payment,type,"unrelated"));
        assertThrows(IllegalStateException.class,()->writer.enqueue(payment,type,null));
        payment.setId(null);assertThrows(IllegalStateException.class,()->writer.enqueue(payment,type,"stored-payer"));
        verifyNoInteractions(outbox);
    }
}
