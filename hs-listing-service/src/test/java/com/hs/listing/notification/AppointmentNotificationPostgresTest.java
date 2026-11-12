package com.hs.listing.notification;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.dto.request.CreateAppointmentRequest;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.ViewingAppointmentService;
import com.hs.storage.config.StorageProperties;
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
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import static com.hs.listing.notification.AppointmentNotificationWriter.Event.*;
import static com.hs.listing.notification.AppointmentNotificationWriter.Side.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in and restricted to a dedicated test DB: Hibernate creates/drops its schema. Never point at dev data. */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL", matches = "jdbc:postgresql://[^/]+/core_booking_notification_test_[A-Za-z0-9_]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}", "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}", "spring.jpa.hibernate.ddl-auto=create-drop",
        "notification.events-topic=${HS_TEST_KAFKA_TOPIC:homespace.notification.mock-only.test.v1}",
        "spring.jpa.open-in-view=false", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ViewingAppointmentService.class, AppointmentNotificationWriter.class})
class AppointmentNotificationPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaRepositories(basePackages = {"com.hs.listing.repository", "com.hs.listing.notification"})
    @EnableJpaAuditing
    static class Config {}

    @Autowired ViewingAppointmentService service;
    @Autowired ViewingAppointmentRepository appointments;
    @Autowired ListingRepository listings;
    @Autowired AppointmentNotificationOutboxRepository outbox;
    @Autowired AppointmentNotificationWriter writer;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean StorageProperties storage;
    TransactionTemplate tx;
    String bookingId;

    @BeforeEach
    void fixture() {
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            outbox.deleteAll();
            appointments.deleteAll();
            listings.deleteAll();
            Listing listing = Listing.builder().id(UUID.randomUUID().toString()).ownerId("owner")
                    .title("Test listing").description("Test").category(ListingCategory.ROOM).status(ListingStatus.PUBLISHED)
                    .availableFrom(LocalDate.now()).areaM2(BigDecimal.TEN).priceAmount(BigDecimal.TEN).currency("VND")
                    .priceUnit(PriceUnit.MONTH).depositType(DepositType.NONE).paymentCycle(PaymentCycle.MONTHLY)
                    .minimumLeaseMonths(1).viewingDays(Set.of(DayOfWeek.values()))
                    .viewingSlots(Set.of(ListingEnums.ViewingSlot.MORNING)).build();
            listings.save(listing);
            bookingId = UUID.randomUUID().toString();
            ViewingAppointment apt = ViewingAppointment.builder().id(bookingId).listing(listing).ownerId("owner")
                    .renterId("renter").renterName("Guest").renterPhone("0901234567")
                    .appointmentDate(LocalDate.now().plusDays(2)).startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(9, 0))
                    .status(AppointmentStatus.PENDING).slotType(ListingEnums.ViewingSlot.MORNING).build();
            appointments.save(apt);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HS_TEST_KAFKA_BROKERS", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "HS_TEST_KAFKA_TOPIC", matches = "homespace\\.notification\\.publisher\\.test\\.[a-zA-Z0-9-]+")
    void publishesCommittedBusinessEventThroughRealKafkaAndMarksItSent() throws Exception {
        String brokers = System.getenv("HS_TEST_KAFKA_BROKERS");
        String topic = System.getenv("HS_TEST_KAFKA_TOPIC");
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", brokers))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        service.approveAppointment("owner", bookingId, null);
        var row = outbox.findAll().getFirst();
        assertEquals(topic, row.getTopic());
        assertNull(row.getSentAt());
        var producer = new DefaultKafkaProducerFactory<String, String>(Map.of(
                "bootstrap.servers", brokers, "key.serializer", StringSerializer.class,
                "value.serializer", StringSerializer.class, "acks", "all", "enable.idempotence", true,
                "max.block.ms", 5000, "request.timeout.ms", 5000, "delivery.timeout.ms", 10000));
        KafkaTemplate<String, String> kafka = new KafkaTemplate<>(producer);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                        "bootstrap.servers", brokers, "group.id", "booking-publisher-test-" + UUID.randomUUID(),
                        "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class,
                        "enable.auto.commit", false, "allow.auto.create.topics", false))) {
            new AppointmentNotificationPublisher(jdbc, kafka, manager).publishPending();
            assertEquals(0L, pending());
            assertNotNull(outbox.findById(row.getId()).orElseThrow().getSentAt());
            var partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> received = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (received.isEmpty() && System.nanoTime() < deadline)
                consumer.poll(Duration.ofMillis(500)).forEach(received::add);
            assertEquals(1, received.size());
            assertEquals(bookingId, received.getFirst().key());
            assertEquals(row.getPayload(), received.getFirst().value());
            assertTrue(received.getFirst().value().contains("BOOKING_CONFIRMED"));
            assertTrue(received.getFirst().value().contains("RENTER"));
        } finally {
            kafka.destroy();
            producer.destroy();
        }
    }

    @Test
    void createLocksTheListingAndPersistsTheOwnerNotification() {
        String listingId = service.getAppointment("renter", bookingId).listingId();
        var created = service.createAppointment("new-renter", null, new CreateAppointmentRequest(listingId,
                LocalDate.now().plusDays(3), LocalTime.of(8, 0), LocalTime.of(9, 0), "New Guest", "0901234567", 1, null));
        assertEquals(AppointmentStatus.PENDING, created.status());
        assertEquals(1L, outbox.count());
        assertEquals(created.id(), outbox.findAll().getFirst().getBookingId());
        assertTrue(outbox.findAll().getFirst().getPayload().contains("BOOKING_REQUESTED"));
        assertThrows(AppException.class, () -> service.getAppointment("outsider", created.id()));
        assertThrows(AppException.class, () -> service.createAppointment("new-renter", null,
                new CreateAppointmentRequest(listingId, LocalDate.now().plusDays(3), LocalTime.of(8, 0),
                        LocalTime.of(9, 0), "New Guest", "0901234567", 1, null)));
        assertEquals(1L, outbox.count());
    }

    @Test
    void businessStateAndNotificationRollbackTogether() {
        tx.executeWithoutResult(status -> {
            service.approveAppointment("owner", bookingId, null);
            assertEquals(1, outbox.count());
            status.setRollbackOnly();
        });
        assertEquals(0, outbox.count());
        assertEquals(AppointmentStatus.PENDING, appointments.findById(bookingId).orElseThrow().getStatus());
    }

    @Test
    void competingApprovalCannotOverwriteCommittedCancellation() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1), release = new CountDownLatch(1), approving = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> cancellation = pool.submit(() -> tx.executeWithoutResult(status -> {
                service.cancelByRenter("renter", bookingId, null);
                cancelled.countDown();
                await(release);
            }));
            assertTrue(cancelled.await(10, TimeUnit.SECONDS));
            Future<?> approval = pool.submit(() -> {
                approving.countDown();
                assertThrows(AppException.class, () -> service.approveAppointment("owner", bookingId, null));
            });
            assertTrue(approving.await(5, TimeUnit.SECONDS));
            try {
                Thread.sleep(150);
                assertFalse(approval.isDone(), "approval must wait for the cancelling transaction's row lock");
            } finally {
                release.countDown();
            }
            cancellation.get(10, TimeUnit.SECONDS);
            approval.get(10, TimeUnit.SECONDS);
        }
        assertEquals(AppointmentStatus.CANCELLED, appointments.findById(bookingId).orElseThrow().getStatus());
        assertEquals(1, outbox.count());
        assertTrue(outbox.findAll().getFirst().getPayload().contains("BOOKING_CANCELLED"));
    }

    @Test
    void failedSendKeepsIdenticalPayloadForRetryAndPreservesBookingOrder() {
        service.approveAppointment("owner", bookingId, null);
        service.cancelByOwner("owner", bookingId, null);
        var original = outbox.findAll().stream().sorted(Comparator.comparing(AppointmentNotificationOutbox::getId)).toList();
        KafkaTemplate<String, String> kafka = kafka();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        var publisher = new AppointmentNotificationPublisher(jdbc, kafka, manager);
        publisher.publishPending();
        assertEquals(2L, pending());
        verify(kafka).send(original.getFirst().getTopic(), bookingId, original.getFirst().getPayload());
        clearInvocations(kafka);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publishPending();
        assertEquals(0L, pending());
        var order = inOrder(kafka);
        order.verify(kafka).send(original.getFirst().getTopic(), bookingId, original.getFirst().getPayload());
        order.verify(kafka).send(original.getLast().getTopic(), bookingId, original.getLast().getPayload());
        order.verifyNoMoreInteractions();
    }

    @Test
    void secondPublisherSkipsLockedHeadWithoutOvertakingTheSameBooking() throws Exception {
        service.approveAppointment("owner", bookingId, null);
        service.cancelByOwner("owner", bookingId, null);
        String otherBooking = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> writer.enqueue(ViewingAppointment.builder().id(otherBooking)
                .ownerId("owner").renterId("renter").build(), BOOKING_REQUESTED, OWNER));
        KafkaTemplate<String, String> first = kafka(), second = kafka();
        CountDownLatch claimed = new CountDownLatch(1);
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> acknowledgement = new CompletableFuture<>();
        when(first.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            claimed.countDown();
            return acknowledgement;
        });
        when(second.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> worker = pool.submit(() -> new AppointmentNotificationPublisher(jdbc, first, manager).publishPending());
            assertTrue(claimed.await(10, TimeUnit.SECONDS));
            try {
                new AppointmentNotificationPublisher(jdbc, second, manager).publishPending();
                verify(second).send(anyString(), eq(otherBooking), anyString());
                verify(second, never()).send(anyString(), eq(bookingId), anyString());
            } finally {
                acknowledgement.complete(null);
            }
            worker.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0L, pending());
        verify(first, times(2)).send(anyString(), eq(bookingId), anyString());
    }

    private long pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointment_notification_outbox WHERE sent_at IS NULL", Long.class);
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafka() { return mock(KafkaTemplate.class); }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out awaiting test transaction");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
