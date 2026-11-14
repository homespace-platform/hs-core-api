package com.hs.contract.notification;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hs.contract.model.Contract;
import com.hs.contract.model.MonthlyInvoice;
import com.hs.contract.notification.InvoiceNotificationWriter.Event;

class InvoiceNotificationWriterTest {
    final InvoiceOutboxProbe probe = new InvoiceOutboxProbe();
    final InvoiceNotificationWriter writer = probe.writer;
    final Contract contract = Contract.builder().id("contract").landlordId("landlord").tenantId("tenant").build();
    final MonthlyInvoice invoice = MonthlyInvoice.builder().id("invoice").contractId("contract").build();

    @Test void thirteenEventsExactlyAndEveryEnqueueMethodRequiresAnExistingTransaction() {
        assertEquals(13, Event.values().length);
        long mandatory = Stream.of(InvoiceNotificationWriter.class.getDeclaredMethods()).filter(m -> m.getName().startsWith("enqueue"))
                .map(m -> m.getAnnotation(Transactional.class)).filter(t -> t != null && t.propagation() == Propagation.MANDATORY).count();
        assertEquals(3, mandatory);
    }

    @Test void landlordTenantAndBothWriteOneEnvelopePerStoredRecipientWithDistinctEventIds() {
        writer.enqueueLandlord(contract, invoice, Event.INVOICE_DRAFT_READY);
        writer.enqueueTenant(contract, invoice, Event.INVOICE_ISSUED);
        writer.enqueueBoth(contract, invoice, Event.INVOICE_OVERDUE);
        assertEquals(List.of("INVOICE_DRAFT_READY:landlord:invoice", "INVOICE_ISSUED:tenant:invoice",
                "INVOICE_OVERDUE:landlord:invoice", "INVOICE_OVERDUE:tenant:invoice"), probe.keys());
        assertEquals(4, probe.sent().stream().map(InvoiceOutboxProbe.Sent::eventId).distinct().count());
        assertTrue(probe.rows().stream().allMatch(r -> r.getAggregateId().equals("invoice") && r.getTopic().equals("homespace.notification.events.v1")
                && r.getSentAt() == null && r.getCreatedAt() != null && r.getEventId().length() == 36));
    }

    @Test void samePartyOnBothSidesProducesOneEnvelope() {
        contract.setTenantId("landlord");
        writer.enqueueBoth(contract, invoice, Event.INVOICE_DEFERRED);
        assertEquals(1, probe.rows().size());
    }

    @Test void invoiceFromAnotherContractOrIncompleteIdentityIsRejectedBeforeAnyWrite() {
        var foreign = MonthlyInvoice.builder().id("other").contractId("elsewhere").build();
        assertThrows(IllegalStateException.class, () -> writer.enqueueTenant(contract, foreign, Event.INVOICE_ISSUED));
        assertThrows(IllegalStateException.class, () -> writer.enqueueTenant(contract, MonthlyInvoice.builder().contractId("contract").build(), Event.INVOICE_ISSUED));
        assertThrows(IllegalStateException.class, () -> writer.enqueueTenant(Contract.builder().id("contract").landlordId("landlord").build(), invoice, Event.INVOICE_ISSUED));
        assertThrows(IllegalStateException.class, () -> writer.enqueueLandlord(null, invoice, Event.INVOICE_ISSUED));
        assertEquals(0, probe.rows().size());
    }

    @Test void envelopeCarriesNoSensitiveOrFinancialFields() {
        invoice.setTotalAmount(new java.math.BigDecimal("123456789"));
        writer.enqueueTenant(contract, invoice, Event.INVOICE_AMOUNT_UPDATED);
        var envelope = probe.sent().getFirst().envelope();
        assertEquals("[\"contractId\",\"invoiceId\"]", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(envelope.get("data").fieldNames(), 0), false).toList()).toString());
        assertFalse(probe.rows().getFirst().getPayload().contains("123456789"));
    }

    /** Real writer output for the Node consumer cross-check; written only when HS_INVOICE_FIXTURE_OUT names a file. */
    @Test void javaEnvelopesForConsumerCrossCheck() throws Exception {
        var live = Contract.builder().id(java.util.UUID.randomUUID().toString()).landlordId("landlord-" + java.util.UUID.randomUUID())
                .tenantId("tenant-" + java.util.UUID.randomUUID()).build();
        var bill = MonthlyInvoice.builder().id(java.util.UUID.randomUUID().toString()).contractId(live.getId()).build();
        for (Event event : Event.values()) {
            if (event == Event.INVOICE_DRAFT_READY || event == Event.INVOICE_EXTENSION_REQUESTED
                    || event == Event.INVOICE_OVERDUE_ACTION_ACKNOWLEDGED || event == Event.INVOICE_OVERDUE_ACTION_REQUIRED)
                writer.enqueueLandlord(live, bill, event);
            else if (event == Event.INVOICE_OVERDUE || event == Event.INVOICE_DEFERRED) writer.enqueueBoth(live, bill, event);
            else writer.enqueueTenant(live, bill, event);
        }
        assertEquals(13, probe.sent().stream().map(InvoiceOutboxProbe.Sent::type).distinct().count());
        String out = System.getenv("HS_INVOICE_FIXTURE_OUT");
        if (out != null && !out.isBlank())
            java.nio.file.Files.write(java.nio.file.Path.of(out), probe.rows().stream().map(r -> r.getPayload()).toList());
    }
}
