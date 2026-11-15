package com.hs.user.notification;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BroadcastNotificationPublisherTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked") final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final List<String> recipients = new ArrayList<>();
    String cursor;
    boolean complete;
    final Timestamp cutoff = Timestamp.from(Instant.parse("2026-11-15T00:00:00Z"));
    BroadcastNotificationPublisher publisher() { return new BroadcastNotificationPublisher(jdbc, kafka, manager); }

    @BeforeEach @SuppressWarnings("unchecked") void database() throws Exception {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var row = mock(ResultSet.class);
        when(row.getString("event_id")).thenReturn("news_123"); when(row.getString("topic")).thenReturn("notification.test");
        when(row.getString("payload")).thenReturn(BroadcastNotificationServiceTest.NEWS);
        when(row.getTimestamp("recipient_cutoff")).thenReturn(cutoff);
        when(row.getString("recipient_cursor")).thenAnswer(call -> cursor);
        when(jdbc.query(eq(BroadcastNotificationPublisher.CLAIM_SQL), any(RowMapper.class))).thenAnswer(call ->
                complete ? List.of() : List.of(((RowMapper<?>)call.getArgument(1)).mapRow(row, 0)));
        when(jdbc.queryForList(eq(BroadcastNotificationPublisher.RECIPIENTS_SQL), eq(String.class), eq(cutoff), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> recipients.stream().filter(id -> cursor == null || id.compareTo(cursor) > 0).limit(100).toList());
        when(jdbc.update(eq(BroadcastNotificationPublisher.ADVANCE_SQL), nullable(String.class), nullable(Timestamp.class), eq("news_123")))
                .thenAnswer(call -> { cursor = call.getArgument(1); complete = call.getArgument(2) != null; return 1; });
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test void sendsBoundedPagesAndOnlyThenCompletesWithFrozenIdentity() throws Exception {
        for (int i = 0; i < 201; i++) recipients.add("user_%03d".formatted(i));
        publisher().publishPending(); assertTrue(complete); assertEquals("user_200", cursor);
        var payloads = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(3)).send(eq("notification.test"), eq("news_123"), payloads.capture());
        assertEquals(List.of(100, 100, 1), payloads.getAllValues().stream().map(p -> BroadcastNotificationService.read(p).get("recipients").size()).toList());
        for (var payload : payloads.getAllValues()) assertEquals("2026-11-15T00:00:00.000Z", BroadcastNotificationService.read(payload).get("occurredAt").asText());
        var order = inOrder(kafka, jdbc);
        order.verify(kafka).send(anyString(), anyString(), eq(payloads.getAllValues().getFirst()));
        order.verify(jdbc).update(eq(BroadcastNotificationPublisher.ADVANCE_SQL), eq("user_099"), isNull(), eq("news_123"));
    }

    @Test void failedAcknowledgementNeverAdvancesAndRestartRetriesSamePage() {
        recipients.addAll(List.of("alice", "bob"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        publisher().publishPending(); assertNull(cursor); assertFalse(complete);
        verify(jdbc, never()).update(eq(BroadcastNotificationPublisher.ADVANCE_SQL), any(), any(), any());
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        publisher().publishPending(); assertTrue(complete); assertEquals("bob", cursor);
        var payloads = ArgumentCaptor.forClass(String.class); verify(kafka, times(2)).send(anyString(), anyString(), payloads.capture());
        assertEquals(payloads.getAllValues().getFirst(), payloads.getAllValues().getLast()); verify(manager).rollback(any());
    }

    @Test void databaseFailureAfterAcknowledgementReplaysExactEventOnRestart() {
        recipients.add("alice");
        when(jdbc.update(eq(BroadcastNotificationPublisher.ADVANCE_SQL), any(), any(), any()))
                .thenThrow(new IllegalStateException("checkpoint unavailable"))
                .thenAnswer(call -> { cursor = call.getArgument(1); complete = true; return 1; });
        publisher().publishPending(); assertFalse(complete); assertNull(cursor);
        publisher().publishPending(); assertTrue(complete);
        var payloads = ArgumentCaptor.forClass(String.class); verify(kafka, times(2)).send(anyString(), eq("news_123"), payloads.capture());
        assertEquals(payloads.getAllValues().getFirst(), payloads.getAllValues().getLast());
    }

    @Test void emptyAudienceCompletesWithoutKafkaAndCompletedJobsStaySilent() {
        publisher().publishPending(); assertTrue(complete); publisher().publishPending(); verifyNoInteractions(kafka);
    }
}
