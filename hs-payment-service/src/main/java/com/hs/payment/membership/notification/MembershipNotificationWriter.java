package com.hs.payment.membership.notification;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.membership.MembershipOverpayment;

/**
 * Enqueues v1 membership notifications for the persisted order owner when a verified provider outcome actually
 * changed the order status. The caller passes the status read under the order lock; same-status replays are silent.
 * The envelope data carries only the membership order id: no amount, tier, provider payload, hash or wallet.
 */
@Service
public class MembershipNotificationWriter {
    public enum Event { MEMBERSHIP_ACTIVATED, MEMBERSHIP_OVERPAYMENT_RECEIVED }

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final MembershipNotificationOutboxRepository outbox;
    private final String topic;

    public MembershipNotificationWriter(MembershipNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    /** PAID -> MEMBERSHIP_ACTIVATED; no event when the status did not change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTransition(MembershipOrder order, MembershipOrder.Status before) {
        MembershipOrder.Status after = order.getStatus();
        if (after == before) return;
        Event type = switch (after) {
            case PAID -> Event.MEMBERSHIP_ACTIVATED;
            default -> throw new IllegalStateException("Membership notification status unsupported");
        };
        enqueue(type, order.getId(), order.getOwnerId());
    }

    /** Second or unappliable payment recorded for refund; the event carries the original order id only. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueOverpayment(MembershipOverpayment overpayment) {
        enqueue(Event.MEMBERSHIP_OVERPAYMENT_RECEIVED, overpayment.getOrderId(), overpayment.getOwnerId());
    }

    private void enqueue(Event type, String orderId, String recipient) {
        if (orderId == null || orderId.isBlank()) throw new IllegalStateException("Membership order missing");
        if (recipient == null || recipient.isBlank()) throw new IllegalStateException("Membership owner missing");
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type.name());
        envelope.put("version", 1);
        envelope.put("occurredAt", TIMESTAMP.format(now));
        envelope.put("recipients", List.of(recipient));
        envelope.put("data", Map.of("orderId", orderId));
        String payload;
        try {
            payload = json.writeValueAsString(envelope);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Membership notification serialization failed", failure);
        }
        outbox.save(new MembershipNotificationOutbox(eventId, orderId, topic, payload, now));
    }
}
