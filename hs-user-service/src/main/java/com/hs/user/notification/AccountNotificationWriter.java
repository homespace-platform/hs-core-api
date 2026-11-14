package com.hs.user.notification;

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
import com.hs.user.model.User;

/**
 * Enqueues v1 account notifications for the persisted user whose role or status an admin changed. Callers decide
 * explicitly which committed transition produces an event; this class never infers events from entity state.
 * The envelope carries empty data: the recipient id is the only identity, so no role, email or admin id leaks.
 */
@Service
public class AccountNotificationWriter {
    public enum Event { ACCOUNT_ROLE_CHANGED, ACCOUNT_DISABLED, ACCOUNT_ENABLED }

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final AccountNotificationOutboxRepository outbox;
    private final String topic;

    public AccountNotificationWriter(AccountNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(User recipient, String actorId, Event type) {
        String recipientId = recipient.getId();
        if (recipientId == null || recipientId.isBlank()) throw new IllegalStateException("Account recipient missing");
        if (recipientId.equals(actorId)) return; // the admin already knows about their own action
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type.name());
        envelope.put("version", 1);
        envelope.put("occurredAt", TIMESTAMP.format(now));
        envelope.put("recipients", List.of(recipientId));
        envelope.put("data", Map.of());
        String payload;
        try {
            payload = json.writeValueAsString(envelope);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Account notification serialization failed", failure);
        }
        outbox.save(new AccountNotificationOutbox(eventId, recipientId, topic, payload, now));
    }
}
