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
    final com.hs.payment.receipt.PaymentReceiptRepository receipts=mock(com.hs.payment.receipt.PaymentReceiptRepository.class);
    final PaymentNotificationWriter writer=new PaymentNotificationWriter(outbox,receipts,"test.payment.notifications");
    final PaymentRequest payment=PaymentRequest.builder().id("cb856b21-5fbf-4d33-b7f7-fd54d5ce7867")
        .payerId("stored-payer").payeeId("stored-payee").rejectedReason("PRIVATE_REASON").build();
    final ObjectMapper json=new ObjectMapper();

    @Test void enqueueOnceWritesOneEventPerDedupeKeyAndOnlyForStoredParties() throws Exception {
        var type=PaymentNotificationWriter.Event.PAYMENT_REFUND_AUTO_CONFIRM_SOON;
        assertTrue(writer.enqueueOnce(payment,type,"stored-payee","report-1"));
        var capture=org.mockito.ArgumentCaptor.forClass(PaymentNotificationOutbox.class);verify(outbox).save(capture.capture());
        var row=capture.getValue();var event=json.readTree(row.getPayload());
        assertEquals(row.getEventId(),event.get("eventId").asText());assertEquals("stored-payee",event.get("recipients").get(0).asText());
        assertEquals(Set.of("paymentId"),fields(event.get("data")));
        when(outbox.existsByEventId(row.getEventId())).thenReturn(true);
        assertFalse(writer.enqueueOnce(payment,type,"stored-payee","report-1"));verify(outbox,times(1)).save(any());
        assertTrue(writer.enqueueOnce(payment,type,"stored-payee","report-2")); // a new report is a new warning
        assertThrows(IllegalStateException.class,()->writer.enqueueOnce(payment,type,"stranger","report-3"));
    }

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
        var type=PaymentNotificationWriter.Event.PAYMENT_RECEIPT_CONFIRMED;
        writer.enqueueOthers(payment,type,"stored-payer");writer.enqueueOthers(payment,type,"outside-admin");
        var capture=org.mockito.ArgumentCaptor.forClass(PaymentNotificationOutbox.class);verify(outbox,times(3)).save(capture.capture());
        var recipients=new ArrayList<String>();for(var row:capture.getAllValues())recipients.add(json.readTree(row.getPayload()).get("recipients").get(0).asText());
        assertEquals(List.of("stored-payee","stored-payer","stored-payee"),recipients);
        assertEquals(3,capture.getAllValues().stream().map(PaymentNotificationOutbox::getEventId).distinct().count());
    }
    @Test void receiptEventsCarryReceiptIdNumberAndAmountButNoSensitiveFields() throws Exception {
        payment.setTotalAmount(new java.math.BigDecimal("1500000.00"));payment.setDueAt(java.time.Instant.parse("2026-12-01T00:00:00Z"));
        payment.setPayerBankAccountSnapshot("PAYER-BANK-9704");payment.setTransferReference("REF-SECRET");
        var receipt=new com.hs.payment.receipt.PaymentReceipt();receipt.setId("receipt-1");receipt.setReceiptNumber("HS-TT-20261128-ABC");
        when(receipts.findByPaymentRequestId(payment.getId())).thenReturn(Optional.of(receipt));
        writer.enqueue(payment,PaymentNotificationWriter.Event.PAYMENT_RECEIPT_CONFIRMED,"stored-payer");
        writer.enqueue(payment,PaymentNotificationWriter.Event.PAYMENT_RECEIPT_AUTO_CONFIRMED,"stored-payee");
        writer.enqueue(payment,PaymentNotificationWriter.Event.PAYMENT_DUE_SOON,"stored-payer");
        writer.enqueue(payment,PaymentNotificationWriter.Event.PAYMENT_TRANSFER_REPORTED,"stored-payee");
        var capture=org.mockito.ArgumentCaptor.forClass(PaymentNotificationOutbox.class);verify(outbox,times(4)).save(capture.capture());
        var rows=capture.getAllValues();
        for(int i=0;i<2;i++){var d=json.readTree(rows.get(i).getPayload()).get("data");
            assertEquals(Set.of("paymentId","amount","receiptId","receiptNumber"),fields(d));
            assertEquals("receipt-1",d.get("receiptId").asText());assertEquals("HS-TT-20261128-ABC",d.get("receiptNumber").asText());
            assertEquals(1500000L,d.get("amount").asLong());assertEquals(payment.getId(),d.get("paymentId").asText());}
        var due=json.readTree(rows.get(2).getPayload()).get("data");
        assertEquals(Set.of("paymentId","amount","dueAt"),fields(due));assertEquals("2026-12-01T00:00:00.000Z",due.get("dueAt").asText());
        assertEquals(Set.of("paymentId","amount"),fields(json.readTree(rows.get(3).getPayload()).get("data")));
        for(var row:rows)for(var secret:List.of("PAYER-BANK-9704","REF-SECRET","PRIVATE_REASON"))assertFalse(row.getPayload().contains(secret));
    }
    @Test void invalidRecipientOrMissingPaymentIdentityCannotEnqueue(){
        var type=PaymentNotificationWriter.Event.PAYMENT_RECEIPT_CONFIRMED;
        assertThrows(IllegalStateException.class,()->writer.enqueue(payment,type,"unrelated"));
        assertThrows(IllegalStateException.class,()->writer.enqueue(payment,type,null));
        payment.setId(null);assertThrows(IllegalStateException.class,()->writer.enqueue(payment,type,"stored-payer"));
        verifyNoInteractions(outbox);
    }
}
