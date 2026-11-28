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
import com.hs.payment.receipt.PaymentReceiptRepository;

/**
 * Enqueues v1 payment notifications for the stored payer/payee of a payment request. Callers decide which actual
 * transition produces an event; recipients are always validated against the persisted parties, never taken from input.
 * Each envelope has one recipient and data {paymentId} plus additive amount (VND), dueAt (request/due-soon/overdue only)
 * and, for the two receipt events, receiptId and receiptNumber. Never bank, reason, file or other-party identity.
 */
@Service
public class PaymentNotificationWriter {
    public enum Event {
        PAYMENT_REQUEST_CREATED, PAYMENT_OBLIGATION_OVERDUE, PAYMENT_DUE_SOON, PAYMENT_TRANSFER_REPORTED, PAYMENT_RECEIPT_CONFIRMED, PAYMENT_RECEIPT_REJECTED,
        PAYMENT_REFUND_AUTO_CONFIRM_SOON, PAYMENT_RECEIPT_AUTO_CONFIRM_SOON, PAYMENT_RECEIPT_AUTO_CONFIRMED
    }

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final PaymentNotificationOutboxRepository outbox;
    private final PaymentReceiptRepository receipts;
    private final String topic;

    public PaymentNotificationWriter(PaymentNotificationOutboxRepository outbox, PaymentReceiptRepository receipts,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.receipts = receipts;
        this.topic = topic;
    }

    /** One event for a recipient that must be the stored payer or payee. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PaymentRequest payment, Event type, String recipient) {
        requirePayment(payment);
        if (recipient == null || recipient.isBlank()
                || !recipient.equals(payment.getPayerId()) && !recipient.equals(payment.getPayeeId()))
            throw new IllegalStateException("Payment notification recipient is not a stored party");
        write(payment, type, recipient, UUID.randomUUID().toString());
    }

    /**
     * At most one event per dedupe key: the event id is derived from it, so reruns and a second instance cannot
     * duplicate it (a lost race fails on the unique event_id and rolls that transaction back).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueueOnce(PaymentRequest payment, Event type, String recipient, String dedupeKey) {
        requirePayment(payment);
        if (recipient == null || recipient.isBlank()
                || !recipient.equals(payment.getPayerId()) && !recipient.equals(payment.getPayeeId()))
            throw new IllegalStateException("Payment notification recipient is not a stored party");
        String eventId = onceEventId(payment, type, dedupeKey);
        if (outbox.existsByEventId(eventId)) return false;
        write(payment, type, recipient, eventId);
        return true;
    }

    private static String onceEventId(PaymentRequest payment, Event type, String dedupeKey) {
        return UUID.nameUUIDFromBytes((type.name() + ":" + payment.getId() + ":" + dedupeKey)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /** When the {@link #enqueueOnce} event for this key was recorded; empty if it never was. */
    public java.util.Optional<Instant> onceCreatedAt(PaymentRequest payment, Event type, String dedupeKey) {
        return outbox.findByEventId(onceEventId(payment, type, dedupeKey)).map(PaymentNotificationOutbox::getCreatedAt);
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

    private void write(PaymentRequest payment, Event type, String recipient, String eventId) {
        String paymentId = payment.getId();
        Instant now = Instant.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", paymentId);
        // VND is whole-dong; rounding is explicit so the JSON number never carries a scale.
        if (payment.getTotalAmount() != null)
            data.put("amount", payment.getTotalAmount().setScale(0, java.math.RoundingMode.HALF_UP).longValue());
        if (payment.getDueAt() != null && (type == Event.PAYMENT_REQUEST_CREATED || type == Event.PAYMENT_DUE_SOON
                || type == Event.PAYMENT_OBLIGATION_OVERDUE)) data.put("dueAt", TIMESTAMP.format(payment.getDueAt()));
        if (type == Event.PAYMENT_RECEIPT_CONFIRMED || type == Event.PAYMENT_RECEIPT_AUTO_CONFIRMED)
            // Captured earlier in the same transaction; absent (zero-amount payment) means no receipt, no attachment.
            receipts.findByPaymentRequestId(paymentId).ifPresent(r -> {
                data.put("receiptId", r.getId());
                data.put("receiptNumber", r.getReceiptNumber());
            });
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
            throw new IllegalStateException("Payment notification serialization failed", failure);
        }
        outbox.save(new PaymentNotificationOutbox(eventId, paymentId, topic, payload, now));
    }
}
