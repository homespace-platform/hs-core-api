package com.hs.listing.notification;

import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.hs.listing.notification.RentalRequestNotificationWriter.Event.*;
import static com.hs.listing.notification.RentalRequestNotificationWriter.Side.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real-PostgreSQL proof of the rental-request outbox. Restricted to the dedicated rental notification test
 * database: Hibernate creates and drops the schema. Never point this at dev or shared data.
 */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL", matches = "jdbc:postgresql://[^/]+/core_rental_notification_test_[A-Za-z0-9]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}", "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}", "spring.jpa.hibernate.ddl-auto=create-drop",
        "notification.events-topic=homespace.notification.mock-only.test.v1",
        "spring.jpa.open-in-view=false", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(RentalRequestNotificationWriter.class)
class RentalRequestNotificationPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses = RentalRequestNotificationOutboxRepository.class)
    @EnableJpaAuditing
    static class Config {}

    @Autowired RentalRequestNotificationOutboxRepository outbox;
    @Autowired RentalRequestNotificationWriter writer;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json = JsonMapper.builder().build();
    TransactionTemplate tx;
    String requestId;
    RentalRequest request;

    @BeforeEach
    void fixture() {
        tx = new TransactionTemplate(manager);
        outbox.deleteAllInBatch();
        requestId = UUID.randomUUID().toString();
        request = request(requestId);
    }

    private RentalRequest request(String id) {
        return RentalRequest.builder().id(id).ownerId("persisted-owner").renterId("persisted-renter")
                .listing(Listing.builder().id("listing").title("Phòng test").build()).build();
    }

    @Test
    void bothWriterApisRequireAnExistingBusinessTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> writer.enqueue(request, RENTAL_REQUEST_SUBMITTED, OWNER));
        assertThrows(IllegalTransactionStateException.class, () -> writer.enqueueOthers(request, "persisted-owner", RENTAL_REQUEST_CANCELLED));
        assertEquals(0, outbox.count());
    }

    @Test
    void enqueueRollsBackWithTheBusinessTransactionAndCommitsAtomically() {
        tx.executeWithoutResult(status -> {
            writer.enqueueOthers(request, "SYSTEM", RENTAL_REQUEST_EXPIRED);
            assertEquals(2, outbox.count());
            status.setRollbackOnly();
        });
        assertEquals(0, outbox.count());
        tx.executeWithoutResult(status -> writer.enqueueOthers(request, "persisted-renter", RENTAL_REQUEST_CANCELLED));
        var rows = rows();
        assertEquals(1, rows.size());
        assertEquals(requestId, rows.getFirst().getRentalRequestId());
        assertNull(rows.getFirst().getSentAt());
        var event = json.readTree(rows.getFirst().getPayload());
        assertEquals("OWNER", event.get("data").get("recipientSide").asText());
        assertEquals("persisted-owner", event.get("recipients").get(0).asText());
    }

    @Test
    void payloadIsFrozenAtEnqueueTimeAndEventIdsAreUnique() {
        tx.executeWithoutResult(status -> {
            writer.enqueue(request, RENTAL_REQUEST_SUBMITTED, OWNER);
            writer.enqueue(request, RENTAL_REQUEST_SUBMITTED, OWNER);
        });
        request.getListing().setTitle("Đổi tên sau khi xếp hàng");
        var rows = rows();
        assertNotEquals(rows.getFirst().getEventId(), rows.getLast().getEventId());
        for (var row : rows) {
            var event = json.readTree(row.getPayload());
            assertEquals(row.getEventId(), event.get("eventId").asText());
            assertEquals("Phòng test", event.get("data").get("listingTitle").asText());
        }
    }

    @Test
    void failedSendRetriesIdenticalPayloadAndPreservesPerRequestOrder() {
        tx.executeWithoutResult(status -> {
            writer.enqueue(request, RENTAL_REQUEST_ACCEPTED, RENTER);
            writer.enqueue(request, RENTAL_CANCELLATION_REQUESTED, OWNER);
        });
        var rows = rows();
        var kafka = kafka();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test broker unavailable")));
        var publisher = new RentalRequestNotificationPublisher(jdbc, kafka, manager);
        publisher.publishPending();
        assertEquals(2, pending());
        verify(kafka).send(rows.getFirst().getTopic(), requestId, rows.getFirst().getPayload());
        verify(kafka, never()).send(anyString(), anyString(), eq(rows.getLast().getPayload()));
        clearInvocations(kafka);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publishPending();
        assertEquals(0, pending());
        var order = inOrder(kafka);
        order.verify(kafka).send(rows.getFirst().getTopic(), requestId, rows.getFirst().getPayload());
        order.verify(kafka).send(rows.getLast().getTopic(), requestId, rows.getLast().getPayload());
        order.verifyNoMoreInteractions();
        assertTrue(rows().stream().allMatch(row -> row.getSentAt() != null));
    }

    @Test
    void brokerAckBeforeDatabaseFailureReplaysTheSameIdentityAndPayload() {
        tx.executeWithoutResult(status -> writer.enqueue(request, RENTAL_REQUEST_REJECTED, RENTER));
        var row = rows().getFirst();
        var kafka = kafka();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var failingJdbc = spy(jdbc);
        doThrow(new IllegalStateException("test crash after broker ack")).when(failingJdbc).update(
                "UPDATE rental_request_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", row.getId());
        new RentalRequestNotificationPublisher(failingJdbc, kafka, manager).publishPending();
        assertEquals(1, pending());
        new RentalRequestNotificationPublisher(jdbc, kafka, manager).publishPending();
        assertEquals(0, pending());
        verify(kafka, times(2)).send(row.getTopic(), requestId, row.getPayload());
        var replayed = outbox.findById(row.getId()).orElseThrow();
        assertEquals(row.getEventId(), replayed.getEventId());
        assertEquals(row.getPayload(), replayed.getPayload());
    }

    @Test
    void concurrentPublisherSkipsTheLockedHeadWithoutOvertakingTheSameRequest() throws Exception {
        String otherRequest = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            writer.enqueue(request, RENTAL_REQUEST_ACCEPTED, RENTER);
            writer.enqueue(request, RENTAL_HOLD_EXPIRED, OWNER);
            writer.enqueue(request(otherRequest), RENTAL_REQUEST_SUBMITTED, OWNER);
        });
        var first = kafka();
        var second = kafka();
        var claimed = new CountDownLatch(1);
        var acknowledgement = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        when(first.send(anyString(), anyString(), anyString())).thenAnswer(call -> { claimed.countDown(); return acknowledgement; });
        when(second.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> worker = pool.submit(() -> new RentalRequestNotificationPublisher(jdbc, first, manager).publishPending());
            try {
                assertTrue(claimed.await(10, TimeUnit.SECONDS));
                assertEquals(3, pending(), "broker acknowledgement is required before sent_at changes");
                new RentalRequestNotificationPublisher(jdbc, second, manager).publishPending();
                verify(second).send(anyString(), eq(otherRequest), anyString());
                verify(second, never()).send(anyString(), eq(requestId), anyString());
            } finally {
                acknowledgement.complete(null);
            }
            worker.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, pending());
        verify(first, times(2)).send(anyString(), eq(requestId), anyString());
    }

    private List<RentalRequestNotificationOutbox> rows() {
        return outbox.findAll().stream().sorted(Comparator.comparing(RentalRequestNotificationOutbox::getId)).toList();
    }

    private long pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM rental_request_notification_outbox WHERE sent_at IS NULL", Long.class);
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafka() {
        return mock(KafkaTemplate.class);
    }
}
