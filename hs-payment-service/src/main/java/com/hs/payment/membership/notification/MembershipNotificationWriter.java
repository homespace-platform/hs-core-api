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

/**
 * Enqueues v1 membership notifications for the persisted order owner when a verified provider outcome actually
 * changed the order status. The caller passes the status read under the order lock; same-status replays are silent.
 * The envelope data carries only the membership order id: no amount, tier, provider payload, hash or wallet.
 */
@Service
public class MembershipNotificationWriter {
    public enum Event { MEMBERSHIP_ACTIVATED, MEMBERSHIP_PAYMENT_REVIEW }

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final MembershipNotificationOutboxRepository outbox;
    private final String topic;

    public MembershipNotificationWriter(MembershipNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    /** PAID -> MEMBERSHIP_ACTIVATED, REVIEW -> MEMBERSHIP_PAYMENT_REVIEW; no event when the status did not change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTransition(MembershipOrder order, MembershipOrder.Status before) {
        MembershipOrder.Status after = order.getStatus();
        if (after == before) return;
        Event type = switch (after) {
            case PAID -> Event.MEMBERSHIP_ACTIVATED;
            case REVIEW -> Event.MEMBERSHIP_PAYMENT_REVIEW;
            default -> throw new IllegalStateException("Membership notification status unsupported");
        };
        String orderId = order.getId();
        String recipient = order.getOwnerId();
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
