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

        new ContractNotificationPublisher(jdbc, kafka, transactions).publishPending();

        verify(kafka).send("events", "contract-key", "{\"eventType\":\"INVOICE_ISSUED\"}");
        verify(jdbc, never()).update(anyString(), eq(1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSingleFailingDigestUsesAtMostTwoAttemptsPerPollAndRemainsPending() throws Exception {
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
        publisher.publishPending();
        verify(kafka, times(2)).send("events", "digest-key", body);

        publisher.publishPending();
        verify(kafka, times(4)).send("events", "digest-key", body);
        verify(jdbc, never()).update(anyString(), eq(1L));
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
