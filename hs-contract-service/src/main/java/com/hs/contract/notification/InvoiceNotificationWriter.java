package com.hs.contract.notification;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.Contract;
import com.hs.contract.model.MonthlyInvoice;

/**
 * Enqueues v1 monthly-invoice notifications for the persisted parties of the locked contract. Callers decide which
 * actual transition produces an event; recipients are always the stored landlord/tenant of the contract that owns the
 * invoice, never an actor or request value. Each envelope has one recipient and data exactly {contractId, invoiceId}:
 * no amount, date, meter value, note, reason or name enters Kafka.
 */
@Service
public class InvoiceNotificationWriter {
    public enum Event {
        INVOICE_DRAFT_READY, INVOICE_ISSUED, INVOICE_OVERDUE, INVOICE_AMOUNT_UPDATED,
        INVOICE_EXTENSION_REQUESTED, INVOICE_EXTENSION_APPROVED, INVOICE_EXTENSION_REJECTED, INVOICE_EXTENSION_EXPIRED,
        INVOICE_OVERDUE_ACTION_RECORDED, INVOICE_OVERDUE_ACTION_ACKNOWLEDGED,
        INVOICE_DEFERRED, INVOICE_ROLLED_OVER, INVOICE_OVERDUE_ACTION_REQUIRED
    }

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final ContractNotificationOutboxRepository outbox;
    private final String topic;

    public InvoiceNotificationWriter(ContractNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueLandlord(Contract contract, MonthlyInvoice invoice, Event type) {
        validate(contract, invoice);
        write(contract, invoice, type, contract.getLandlordId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTenant(Contract contract, MonthlyInvoice invoice, Event type) {
        validate(contract, invoice);
        write(contract, invoice, type, contract.getTenantId());
    }

    /** Landlord then tenant, one envelope each; a contract whose parties coincide produces a single envelope. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueBoth(Contract contract, MonthlyInvoice invoice, Event type) {
        validate(contract, invoice);
        var recipients = new LinkedHashSet<String>();
        recipients.add(contract.getLandlordId());
        recipients.add(contract.getTenantId());
        for (String recipient : recipients) write(contract, invoice, type, recipient);
    }

    private static void validate(Contract contract, MonthlyInvoice invoice) {
        if (contract == null || contract.getId() == null || contract.getId().isBlank())
            throw new IllegalStateException("Invoice notification contract missing");
        if (invoice == null || invoice.getId() == null || invoice.getId().isBlank())
            throw new IllegalStateException("Invoice notification invoice missing");
        if (!contract.getId().equals(invoice.getContractId()))
            throw new IllegalStateException("Invoice notification invoice does not belong to the locked contract");
        if (contract.getLandlordId() == null || contract.getLandlordId().isBlank()
                || contract.getTenantId() == null || contract.getTenantId().isBlank())
            throw new IllegalStateException("Invoice notification parties missing");
    }

    private void write(Contract contract, MonthlyInvoice invoice, Event type, String recipient) {
        if (!recipient.equals(contract.getLandlordId()) && !recipient.equals(contract.getTenantId()))
            throw new IllegalStateException("Invoice notification recipient is not a stored party");
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contractId", contract.getId());
        data.put("invoiceId", invoice.getId());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type.name());
        envelope.put("version", 1);
        envelope.put("occurredAt", TIMESTAMP.format(now));
        envelope.put("recipients", List.of(recipient));
        envelope.put("data", data);
        String payload;
        try {
            payload = json.writeValueAsString(envelope);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invoice notification serialization failed", failure);
        }
        outbox.save(new ContractNotificationOutbox(eventId, invoice.getId(), topic, payload, now));
    }
}
