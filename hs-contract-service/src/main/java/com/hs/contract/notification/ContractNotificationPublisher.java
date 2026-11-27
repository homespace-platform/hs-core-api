package com.hs.contract.notification;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
public class ContractNotificationPublisher {
    private static final String DIGEST_ROW = "o.payload LIKE '%\"eventType\":\"BRANCH_DAILY_DIGEST\"%'";
    private static final long DIGEST_COOLDOWN_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long DIGEST_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(2);
    // A locked earlier row remains visible as unsent, so another worker cannot overtake it for the same invoice.
    static final String CLAIM_SQL = """
            SELECT o.id, o.aggregate_id, o.topic, o.payload
            FROM contract_notification_outbox o
            WHERE o.sent_at IS NULL AND NOT EXISTS (
              SELECT 1 FROM contract_notification_outbox earlier
              WHERE earlier.aggregate_id = o.aggregate_id AND earlier.sent_at IS NULL AND earlier.id < o.id)
              AND NOT %s
            ORDER BY o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            """.formatted(DIGEST_ROW);
    // Daily digests have their own claim, so their order and failures never reach the contract path.
    private static final String DIGEST_CLAIM_SQL = """
            SELECT o.id, o.aggregate_id, o.topic, o.payload
            FROM contract_notification_outbox o
            WHERE o.sent_at IS NULL AND %s%s
            ORDER BY o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            """;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    // shortcut: per instance and lost on restart; persist a retry time if that ever matters.
    private final Map<Long, Long> digestRetryAt = new ConcurrentHashMap<>();

    public ContractNotificationPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-delay-ms:1000}")
    public void publishPending() {
        for (int i = 0; i < 20; i++) {
            try {
                if (!Boolean.TRUE.equals(transaction.execute(status -> publishOne(CLAIM_SQL, null)))) return;
            } catch (RuntimeException failure) {
                log.warn("Contract notification publish deferred ({})", failure.getClass().getSimpleName());
                return;
            }
        }
    }

    /**
     * A task of its own on the shared scheduler, so a slow digest ACK never holds back the contract poll.
     * Stops at the first failure; that row then cools down, so later polls reach the other digests. The time budget
     * hands the scheduler thread back when sends are slow.
     */
    @Scheduled(fixedDelayString = "${notification.outbox.poll-delay-ms:1000}")
    public void publishDigests() {
        long start = System.nanoTime();
        digestRetryAt.values().removeIf(retryAt -> start - retryAt >= 0);
        for (int i = 0; i < 20 && System.nanoTime() - start < DIGEST_BUDGET_NANOS; i++) {
            var claimed = new AtomicLong();
            String cooling = digestRetryAt.isEmpty() ? "" : digestRetryAt.keySet().stream()
                    .map(String::valueOf).collect(Collectors.joining(",", " AND o.id NOT IN (", ")"));
            try {
                if (!Boolean.TRUE.equals(transaction.execute(status ->
                        publishOne(DIGEST_CLAIM_SQL.formatted(DIGEST_ROW, cooling), claimed)))) break;
            } catch (RuntimeException failure) {
                log.warn("Branch digest publish deferred ({})", failure.getClass().getSimpleName());
                // Covers the send, the sent_at update and the commit.
                if (claimed.get() != 0) digestRetryAt.put(claimed.get(), System.nanoTime() + DIGEST_COOLDOWN_NANOS);
                return;
            }
        }
    }

    private boolean publishOne(String claimSql, AtomicLong claimed) {
        var rows = jdbc.query(claimSql, (rs, index) -> new Pending(
                rs.getLong("id"), rs.getString("aggregate_id"), rs.getString("topic"), rs.getString("payload")));
        if (rows.isEmpty()) return false;
        Pending row = rows.getFirst();
        if (claimed != null) claimed.set(row.id());
        try {
            kafka.send(row.topic(), row.aggregateId(), row.payload()).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Contract notification send interrupted", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("Contract notification send failed", failure);
        }
        jdbc.update("UPDATE contract_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", row.id());
        return true;
    }

    private record Pending(long id, String aggregateId, String topic, String payload) {}
}
