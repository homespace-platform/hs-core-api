package com.hs.payment.notification;

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
public class PaymentNotificationPublisher {
    // A locked earlier row remains visible as unsent, so another worker cannot overtake it for the same payment.
    static final String CLAIM_SQL = """
            SELECT o.id, o.payment_id, o.topic, o.payload
            FROM payment_notification_outbox o
            WHERE o.sent_at IS NULL AND NOT EXISTS (
              SELECT 1 FROM payment_notification_outbox earlier
              WHERE earlier.payment_id = o.payment_id AND earlier.sent_at IS NULL AND earlier.id < o.id)
            ORDER BY o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            """;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;

    public PaymentNotificationPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
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
                log.warn("Payment notification publish deferred ({})", failure.getClass().getSimpleName());
                return;
            }
        }
    }

    private boolean publishOne() {
        var rows = jdbc.query(CLAIM_SQL, (rs, index) -> new Pending(
                rs.getLong("id"), rs.getString("payment_id"), rs.getString("topic"), rs.getString("payload")));
        if (rows.isEmpty()) return false;
        Pending row = rows.getFirst();
        try {
            kafka.send(row.topic(), row.paymentId(), row.payload()).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Payment notification send interrupted", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("Payment notification send failed", failure);
        }
        jdbc.update("UPDATE payment_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", row.id());
        return true;
    }

    private record Pending(long id, String paymentId, String topic, String payload) {}
}
