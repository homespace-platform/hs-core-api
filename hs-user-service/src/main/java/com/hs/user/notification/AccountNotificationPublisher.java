package com.hs.user.notification;

import java.util.concurrent.TimeUnit;

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
public class AccountNotificationPublisher {
    // A locked earlier row remains visible as unsent, so another worker cannot overtake it for the same user.
    static final String CLAIM_SQL = """
            SELECT o.id, o.user_id, o.topic, o.payload
            FROM account_notification_outbox o
            WHERE o.sent_at IS NULL AND NOT EXISTS (
              SELECT 1 FROM account_notification_outbox earlier
              WHERE earlier.user_id = o.user_id AND earlier.sent_at IS NULL AND earlier.id < o.id)
            ORDER BY o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            """;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;

    public AccountNotificationPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            PlatformTransactionManager manager) {
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
                log.warn("Account notification publish deferred ({})", failure.getClass().getSimpleName());
                return;
            }
        }
    }

    private boolean publishOne() {
        var rows = jdbc.query(CLAIM_SQL, (rs, index) -> new Pending(
                rs.getLong("id"), rs.getString("user_id"), rs.getString("topic"), rs.getString("payload")));
        if (rows.isEmpty()) return false;
        Pending row = rows.getFirst();
        try {
            kafka.send(row.topic(), row.userId(), row.payload()).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Account notification send interrupted", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("Account notification send failed", failure);
        }
        jdbc.update("UPDATE account_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", row.id());
        return true;
    }

    private record Pending(long id, String userId, String topic, String payload) {}
}
