package com.hs.payment.notification;

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
import com.hs.payment.model.PaymentRequest;

/**
 * Enqueues v1 payment notifications for the stored payer/payee of a payment request. Callers decide which actual
 * transition produces an event; recipients are always validated against the persisted parties, never taken from input.
 * Each envelope has one recipient and data exactly {paymentId}: no amount, bank, reason, file or other-party identity.
 */
@Service
public class PaymentNotificationWriter {
    public enum Event {
        PAYMENT_REQUEST_CREATED, PAYMENT_OBLIGATION_OVERDUE, PAYMENT_TRANSFER_REPORTED, PAYMENT_RECEIPT_CONFIRMED, PAYMENT_RECEIPT_REJECTED,
        PAYMENT_DISPUTE_OPENED, PAYMENT_DISPUTE_EVIDENCE_ADDED, PAYMENT_DISPUTE_PROPOSED,
        PAYMENT_DISPUTE_PROPOSAL_DECLINED, PAYMENT_DISPUTE_ESCALATED,
        PAYMENT_DISPUTE_REVIEW_TRANSFER, PAYMENT_DISPUTE_REOPENED
    }

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final PaymentNotificationOutboxRepository outbox;
    private final String topic;

    public PaymentNotificationWriter(PaymentNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    /** One event for a recipient that must be the stored payer or payee. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PaymentRequest payment, Event type, String recipient) {
        requirePayment(payment);
        if (recipient == null || recipient.isBlank()
                || !recipient.equals(payment.getPayerId()) && !recipient.equals(payment.getPayeeId()))
            throw new IllegalStateException("Payment notification recipient is not a stored party");
        write(payment.getId(), type, recipient);
    }

    /** Every distinct stored party except the actor; an actor that is not a party (admin) reaches both. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueOthers(PaymentRequest payment, Event type, String actor) {
        requirePayment(payment);
        var recipients = new LinkedHashSet<String>();
        recipients.add(payment.getPayerId());
        recipients.add(payment.getPayeeId());
        recipients.remove(actor);
        for (String recipient : recipients) enqueue(payment, type, recipient);
    }

    private static void requirePayment(PaymentRequest payment) {
        if (payment.getId() == null || payment.getId().isBlank())
            throw new IllegalStateException("Payment notification payment missing");
        if (payment.getPayerId() == null || payment.getPayerId().isBlank()
                || payment.getPayeeId() == null || payment.getPayeeId().isBlank())
            throw new IllegalStateException("Payment notification parties missing");
    }

    private void write(String paymentId, Event type, String recipient) {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type.name());
        envelope.put("version", 1);
        envelope.put("occurredAt", TIMESTAMP.format(now));
        envelope.put("recipients", List.of(recipient));
        envelope.put("data", Map.of("paymentId", paymentId));
        String payload;
        try {
            payload = json.writeValueAsString(envelope);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Payment notification serialization failed", failure);
        }
        outbox.save(new PaymentNotificationOutbox(eventId, paymentId, topic, payload, now));
    }
}
