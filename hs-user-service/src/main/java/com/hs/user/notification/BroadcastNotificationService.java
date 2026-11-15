package com.hs.user.notification;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hs.common.advice.entity.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BroadcastNotificationService {
    static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static final String INSERT_SQL = """
            INSERT INTO notification_broadcast_outbox (event_id, topic, payload, recipient_cutoff, created_at)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
            """;
    private final JdbcTemplate jdbc;
    private final String topic;

    public BroadcastNotificationService(JdbcTemplate jdbc,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.jdbc = jdbc;
        this.topic = topic;
    }

    @Transactional
    public void enqueueAdmin(AdminBroadcastRequest request) {
        Instant now = Instant.now();
        var data = JSON.valueToTree(Map.of("title", request.title().trim(), "body", request.body().trim(),
                "priority", request.priority().name(), "actionPath", request.actionPath() == null ? "/notifications" : request.actionPath()));
        enqueue("admin_" + request.broadcastId().toLowerCase(java.util.Locale.ROOT), "PLATFORM_ANNOUNCEMENT", TIMESTAMP.format(now), data, now, false);
    }

    @Transactional
    public void enqueueNews(String payload) {
        JsonNode event = parseNews(payload);
        enqueue(event.get("eventId").textValue(), "NEWS_PUBLISHED", event.get("occurredAt").textValue(),
                event.get("data"), Instant.parse(event.get("occurredAt").textValue()), true);
    }

    private void enqueue(String eventId, String eventType, String occurredAt, JsonNode data, Instant cutoff, boolean compareTime) {
        var event = JSON.createObjectNode();
        event.put("eventId", eventId).put("eventType", eventType).put("version", 1).put("occurredAt", occurredAt);
        event.putArray("recipients");
        event.set("data", data);
        int inserted = jdbc.update(INSERT_SQL, eventId, topic, event.toString(), Timestamp.from(cutoff), Timestamp.from(Instant.now()));
        if (inserted == 0) {
            var existing = read(jdbc.queryForObject("SELECT payload FROM notification_broadcast_outbox WHERE event_id = ?", String.class, eventId));
            if (!existing.path("eventType").equals(event.get("eventType")) || !existing.path("data").equals(data)
                    || (compareTime && !existing.path("occurredAt").equals(event.get("occurredAt")))) throw new IdConflict();
        }
    }

    static JsonNode parseNews(String payload) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 65536) throw new InvalidNewsEvent();
        JsonNode event;
        try { event = JSON.readTree(payload); } catch (JsonProcessingException failure) { throw new InvalidNewsEvent(); }
        if (!hasKeys(event, Set.of("eventId", "eventType", "version", "occurredAt", "recipients", "data"))
                || !event.path("version").isIntegralNumber() || !event.path("version").canConvertToInt() || event.path("version").intValue() != 1
                || !event.path("eventType").asText().equals("NEWS_PUBLISHED")
                || !event.path("recipients").isArray() || !event.path("recipients").isEmpty()
                || !hasKeys(event.path("data"), Set.of("newsId", "slug"))) throw new InvalidNewsEvent();
        String newsId = event.path("data").path("newsId").asText("");
        String eventId = event.path("eventId").asText("");
        String slug = event.path("data").path("slug").asText("");
        String occurredAt = event.path("occurredAt").asText("");
        if (!event.path("data").path("newsId").isTextual() || !newsId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,194}")
                || !eventId.equals("news_" + newsId) || !event.path("data").path("slug").isTextual()
                || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length() > 255
                || !occurredAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")) throw new InvalidNewsEvent();
        try {
            if (!TIMESTAMP.format(Instant.parse(occurredAt)).equals(occurredAt)) throw new InvalidNewsEvent();
        } catch (java.time.DateTimeException failure) { throw new InvalidNewsEvent(); }
        return event;
    }

    private static boolean hasKeys(JsonNode value, Set<String> keys) {
        if (value == null || !value.isObject() || value.size() != keys.size()) return false;
        return keys.stream().allMatch(value::has);
    }

    static JsonNode read(String payload) {
        try { return JSON.readTree(payload); } catch (JsonProcessingException failure) { throw new IllegalStateException("Stored broadcast is invalid", failure); }
    }

    public static class InvalidNewsEvent extends IllegalArgumentException {}
    public static class IdConflict extends AppException {
        public IdConflict() { super(2019, "Broadcast ID already has different content", HttpStatus.CONFLICT); }
    }
}
