package com.hs.contract.notification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchDigestPublisherTest {
    @Test
    @SuppressWarnings("unchecked")
    void aFailedDigestMustNotKeepAReadyContractNoticeFromPublishing() throws Exception {
        JdbcTemplate jdbc = mock();
        KafkaTemplate<String, String> kafka = mock();
        PlatformTransactionManager transactions = mock();
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        ResultSet digest = row(1L, "digest-key", "{\"eventType\":\"BRANCH_DAILY_DIGEST\"}");
        ResultSet contract = row(2L, "contract-key", "{\"eventType\":\"INVOICE_ISSUED\"}");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(digest, 0));
        }).thenAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(contract, 0));
        }).thenReturn(List.of());
        when(kafka.send(eq("events"), eq("digest-key"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("record rejected")));
        when(kafka.send(eq("events"), eq("contract-key"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        var publisher = new ContractNotificationPublisher(jdbc, kafka, transactions);
        publisher.publishDigests();
        publisher.publishPending();

        verify(kafka).send("events", "contract-key", "{\"eventType\":\"INVOICE_ISSUED\"}");
        verify(jdbc, never()).update(anyString(), eq(1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSingleFailingDigestUsesOneAttemptPerDigestPollAndRemainsPending() throws Exception {
        JdbcTemplate jdbc = mock();
        KafkaTemplate<String, String> kafka = mock();
        PlatformTransactionManager transactions = mock();
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        String body = "{\"eventType\":\"BRANCH_DAILY_DIGEST\"}";
        ResultSet digest = row(1L, "digest-key", body);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(digest, 0));
        });
        when(kafka.send("events", "digest-key", body))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("record rejected")));

        var publisher = new ContractNotificationPublisher(jdbc, kafka, transactions);
        publisher.publishDigests();
        verify(kafka).send("events", "digest-key", body);

        publisher.publishDigests();
        verify(kafka, times(2)).send("events", "digest-key", body);
        verify(jdbc, never()).update(anyString(), eq(1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSlowDigestAckMustNotDelayTheContractPoll() throws Exception {
        JdbcTemplate jdbc = mock();
        KafkaTemplate<String, String> kafka = mock();
        PlatformTransactionManager transactions = mock();
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        ResultSet digest = row(1L, "digest-key", "{\"eventType\":\"BRANCH_DAILY_DIGEST\"}");
        ResultSet contract = row(2L, "contract-key", "{\"eventType\":\"INVOICE_ISSUED\"}");
        var pendingContracts = new java.util.concurrent.atomic.AtomicInteger(1);
        // Only the contract claim carries the per-aggregate NOT EXISTS clause.
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(1);
            if (!call.<String>getArgument(0).contains("NOT EXISTS")) return List.of(mapper.mapRow(digest, 0));
            return pendingContracts.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? List.of(mapper.mapRow(contract, 0)) : List.of();
        });
        var ack = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        when(kafka.send(eq("events"), eq("digest-key"), anyString())).thenReturn(ack);
        when(kafka.send(eq("events"), eq("contract-key"), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var publisher = new ContractNotificationPublisher(jdbc, kafka, transactions);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var digestWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // Its own scheduled task, left waiting on the ACK for the whole test.
            org.junit.jupiter.api.Assertions.assertNotNull(ContractNotificationPublisher.class.getMethod("publishDigests")
                    .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class));
            digestWorker.submit(publisher::publishDigests);
            verify(kafka, timeout(3000)).send(eq("events"), eq("digest-key"), anyString());
            worker.submit(publisher::publishPending).get(3, java.util.concurrent.TimeUnit.SECONDS);
            pendingContracts.set(1);
            worker.submit(publisher::publishPending).get(3, java.util.concurrent.TimeUnit.SECONDS);
            verify(kafka, times(2)).send(eq("events"), eq("contract-key"), anyString());
        } finally {
            ack.complete(null);
            worker.shutdownNow();
            digestWorker.shutdownNow();
        }
    }

    private static ResultSet row(long id, String key, String body) throws Exception {
        ResultSet row = mock();
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("aggregate_id")).thenReturn(key);
        when(row.getString("topic")).thenReturn("events");
        when(row.getString("payload")).thenReturn(body);
        return row;
    }
}
