package com.hs.user.notification;

import com.hs.user.model.*;
import com.hs.user.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// create-drop is permitted only for a fresh, explicitly named notification rollout test database.
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL", matches = "jdbc:postgresql://[^/]+/core_notification_rollout_test_[A-Za-z0-9]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = BroadcastNotificationPostgresTest.Config.class)
@TestPropertySource(properties = {"spring.datasource.url=${HS_TEST_PG_URL}", "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'", "spring.jpa.open-in-view=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(BroadcastNotificationService.class)
class BroadcastNotificationPostgresTest {
    @SpringBootConfiguration @EntityScan({"com.hs.user.model", "com.hs.user.notification"})
    @EnableJpaRepositories(basePackageClasses = UserRepository.class) static class Config {}
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired BroadcastNotificationService service;
    @Autowired PlatformTransactionManager manager;
    String userRole;
    static final Instant CUTOFF = Instant.parse("2026-11-15T00:00:00Z");
    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM notification_broadcast_outbox"); users.deleteAllInBatch(); roles.deleteAllInBatch();
        userRole = roles.saveAndFlush(Role.builder().name("USER").build()).getId();
    }
    void user(String id, String role, boolean active, Instant createdAt) {
        User u = new User(); u.setId(id); u.setUsername(id); u.setEmail(id + "@example.invalid"); u.setActive(active);
        u.setRole(roles.findById(role).orElseThrow()); u.setCreatedAt(createdAt); users.saveAndFlush(u);
    }
    @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka() {
        KafkaTemplate<String, String> result = mock(KafkaTemplate.class);
        when(result.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null)); return result;
    }
    long pending() { return jdbc.queryForObject("SELECT COUNT(*) FROM notification_broadcast_outbox WHERE sent_at IS NULL", Long.class); }
    String cursor() { return jdbc.queryForObject("SELECT recipient_cursor FROM notification_broadcast_outbox WHERE event_id = 'news_123'", String.class); }

    @Test void recipientsAreActiveUsersAtCutoffIncludingLegacyNullCreationDatesAndEmptyJobsFinish() {
        String landlord = roles.saveAndFlush(Role.builder().name("LANDLORD").build()).getId();
        user("alice", userRole, true, CUTOFF.minusSeconds(1)); user("legacy", userRole, true, null);
        user("later", userRole, true, CUTOFF.plusSeconds(1)); user("disabled", userRole, false, CUTOFF.minusSeconds(1));
        user("landlord", landlord, true, CUTOFF.minusSeconds(1)); service.enqueueNews(BroadcastNotificationServiceTest.NEWS);
        var kafka = kafka(); new BroadcastNotificationPublisher(jdbc, kafka, manager).publishPending(); assertEquals(0, pending());
        var payload = org.mockito.ArgumentCaptor.forClass(String.class); verify(kafka).send(anyString(), eq("news_123"), payload.capture());
        assertEquals(List.of("alice", "legacy"), BroadcastNotificationService.JSON.convertValue(
                BroadcastNotificationService.read(payload.getValue()).get("recipients"), List.class));
        jdbc.update("DELETE FROM notification_broadcast_outbox"); users.deleteAllInBatch(); service.enqueueNews(BroadcastNotificationServiceTest.NEWS);
        var empty = kafka(); new BroadcastNotificationPublisher(jdbc, empty, manager).publishPending(); assertEquals(0, pending()); verifyNoInteractions(empty);
    }

    @Test void concurrentSameIdAcceptsOnceAndDifferentContentConflictsWithoutChangingTemplate() throws Exception {
        var request = new AdminBroadcastRequest(UUID.randomUUID().toString(), "Title", "Body", AdminBroadcastRequest.Priority.NORMAL, null);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Void> enqueue = () -> { assertTrue(start.await(10, TimeUnit.SECONDS)); service.enqueueAdmin(request); return null; };
            var a = pool.submit(enqueue); var b = pool.submit(enqueue); start.countDown(); a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, pending()); String payload = jdbc.queryForObject("SELECT payload FROM notification_broadcast_outbox", String.class);
        assertThrows(BroadcastNotificationService.IdConflict.class, () -> service.enqueueAdmin(new AdminBroadcastRequest(
                request.broadcastId(), "Different", "Body", request.priority(), null)));
        assertEquals(payload, jdbc.queryForObject("SELECT payload FROM notification_broadcast_outbox", String.class));
    }

    @Test void checkpointSurvivesRestartAndFailedSendCannotSkipTheNextPage() {
        for (int i = 0; i < 101; i++) user("user_%03d".formatted(i), userRole, true, CUTOFF.minusSeconds(1));
        service.enqueueNews(BroadcastNotificationServiceTest.NEWS); var first = kafka();
        when(first.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null), CompletableFuture.failedFuture(new IllegalStateException("offline")));
        new BroadcastNotificationPublisher(jdbc, first, manager).publishPending(); assertEquals("user_099", cursor()); assertEquals(1, pending());
        var restarted = kafka(); new BroadcastNotificationPublisher(jdbc, restarted, manager).publishPending(); assertEquals(0, pending());
        var payload = org.mockito.ArgumentCaptor.forClass(String.class); verify(restarted).send(anyString(), eq("news_123"), payload.capture());
        assertEquals("user_100", BroadcastNotificationService.read(payload.getValue()).get("recipients").get(0).asText());
    }

    @Test void ackThenCheckpointFailureRollsBackCursorAndReplaysExactEvent() {
        user("alice", userRole, true, CUTOFF.minusSeconds(1)); service.enqueueNews(BroadcastNotificationServiceTest.NEWS);
        var kafka = kafka(); var failure = spy(jdbc);
        doThrow(new IllegalStateException("after ack")).when(failure).update(eq(BroadcastNotificationPublisher.ADVANCE_SQL), any(), any(), any());
        new BroadcastNotificationPublisher(failure, kafka, manager).publishPending(); assertNull(cursor()); assertEquals(1, pending());
        new BroadcastNotificationPublisher(jdbc, kafka, manager).publishPending(); assertEquals(0, pending());
        var payloads = org.mockito.ArgumentCaptor.forClass(String.class); verify(kafka, times(2)).send(anyString(), eq("news_123"), payloads.capture());
        assertEquals(payloads.getAllValues().getFirst(), payloads.getAllValues().getLast());
    }

    @Test void concurrentWorkerSkipsLockedJobAndSendsAnotherWithoutDuplicatingThePage() throws Exception {
        user("alice", userRole, true, CUTOFF.minusSeconds(1)); service.enqueueNews(BroadcastNotificationServiceTest.NEWS);
        var first = kafka(); var second = kafka(); var claimed = new CountDownLatch(1);
        var ack = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        when(first.send(anyString(), anyString(), anyString())).thenAnswer(call -> { claimed.countDown(); return ack; });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var work = pool.submit(() -> new BroadcastNotificationPublisher(jdbc, first, manager).publishPending());
            try {
                assertTrue(claimed.await(10, TimeUnit.SECONDS));
                var admin = new AdminBroadcastRequest(UUID.randomUUID().toString(), "Title", "Body", AdminBroadcastRequest.Priority.HIGH, null);
                service.enqueueAdmin(admin); new BroadcastNotificationPublisher(jdbc, second, manager).publishPending();
                verify(second).send(anyString(), eq("admin_" + admin.broadcastId()), anyString());
                verify(second, never()).send(anyString(), eq("news_123"), anyString()); assertEquals(1, pending());
            } finally { ack.complete(null); }
            work.get(15, TimeUnit.SECONDS);
        }
        assertEquals(0, pending()); verify(first).send(anyString(), eq("news_123"), anyString());
    }
}
