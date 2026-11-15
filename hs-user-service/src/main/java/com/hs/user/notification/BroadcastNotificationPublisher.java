package com.hs.user.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@ConditionalOnProperty(name = "notification.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class BroadcastNotificationPublisher {
    static final String CLAIM_SQL = """
            SELECT event_id, topic, payload, recipient_cutoff, recipient_cursor FROM notification_broadcast_outbox
            WHERE sent_at IS NULL ORDER BY created_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """;
    static final String RECIPIENTS_SQL = """
            SELECT u.id FROM users u JOIN roles r ON r.id = u.role_id
            WHERE u.active = true AND r.name = 'USER' AND (u.created_at IS NULL OR u.created_at <= ?)
              AND (CAST(? AS varchar) IS NULL OR u.id > ?)
            ORDER BY u.id LIMIT 100
            """;
    static final String ADVANCE_SQL = "UPDATE notification_broadcast_outbox SET recipient_cursor = ?, sent_at = ? WHERE event_id = ?";
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;

    public BroadcastNotificationPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-delay-ms:1000}")
    public void publishPending() {
        for (int i = 0; i < 20; i++) {
            try {
                if (!Boolean.TRUE.equals(transaction.execute(status -> publishOne()))) return;
            } catch (RuntimeException failure) {
                log.warn("Broadcast notification publish deferred ({})", failure.getClass().getSimpleName());
                return;
            }
        }
    }

    private boolean publishOne() {
        var rows = jdbc.query(CLAIM_SQL, (rs, index) -> new Pending(rs.getString("event_id"), rs.getString("topic"),
                rs.getString("payload"), rs.getTimestamp("recipient_cutoff"), rs.getString("recipient_cursor")));
        if (rows.isEmpty()) return false;
        Pending row = rows.getFirst();
        var recipients = jdbc.queryForList(RECIPIENTS_SQL, String.class, row.cutoff(), row.cursor(), row.cursor());
        if (!recipients.isEmpty()) {
            ObjectNode event = (ObjectNode) BroadcastNotificationService.read(row.payload());
            event.set("recipients", BroadcastNotificationService.JSON.valueToTree(recipients));
            try {
                kafka.send(row.topic(), row.eventId(), event.toString()).get(15, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Broadcast send interrupted", failure);
            } catch (Exception failure) { throw new IllegalStateException("Broadcast send failed", failure); }
        }
        jdbc.update(ADVANCE_SQL, recipients.isEmpty() ? row.cursor() : recipients.getLast(),
                recipients.size() < 100 ? Timestamp.from(Instant.now()) : null, row.eventId());
        return true;
    }

    private record Pending(String eventId, String topic, String payload, Timestamp cutoff, String cursor) {}
}
