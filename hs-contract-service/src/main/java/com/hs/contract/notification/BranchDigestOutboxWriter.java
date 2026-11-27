package com.hs.contract.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One outbox row per owner and Vietnam day; the row stays after sending and is the day's dedupe key. */
@Service
public class BranchDigestOutboxWriter {
    // Not an existence check: the unique event_id decides between instances without failing the transaction.
    private static final String INSERT_SQL = """
            INSERT INTO contract_notification_outbox (event_id, aggregate_id, topic, payload, created_at)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
            """;
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final JdbcTemplate jdbc;
    private final String topic;
    private final ObjectMapper json = new ObjectMapper();

    public BranchDigestOutboxWriter(JdbcTemplate jdbc,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.jdbc = jdbc;
        this.topic = topic;
    }

    /** @return false when this owner already has the day's digest. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean write(String ownerId, LocalDate day, int attentionCount) {
        String eventId = UUID.nameUUIDFromBytes(
                ("BRANCH_DAILY_DIGEST:" + ownerId + ":" + day).getBytes(StandardCharsets.UTF_8)).toString();
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", "BRANCH_DAILY_DIGEST");
        payload.put("version", 1);
        payload.put("occurredAt", TIMESTAMP.format(now));
        payload.put("recipients", List.of(ownerId));
        payload.put("data", Map.of("attentionCount", Integer.toString(attentionCount)));
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Branch digest serialization failed", failure);
        }
        // Its own aggregate (and Kafka key), so a digest is never ordered with a contract's notices.
        return jdbc.update(INSERT_SQL, eventId, eventId, topic, body, now.atOffset(ZoneOffset.UTC)) == 1;
    }
}
