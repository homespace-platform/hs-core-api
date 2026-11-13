package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.repository.ContractRepository;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.*;
import com.hs.listing.notification.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import com.hs.user.repository.UserRepository;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static com.hs.listing.notification.ListingNotificationWriter.Event.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Needs contract entities because the real listing occupancy guard queries contracts and closures. */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL", matches = "jdbc:postgresql://[^/]+/core_booking_notification_test_listing_[A-Za-z0-9_]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ListingNotificationPostgresTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}", "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "listing.publication-duration-days=30", "notification.events-topic=homespace.notification.mock-only.test.v1"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ListingService.class, ListingAdminService.class, ListingStatusService.class, ListingNotificationWriter.class})
class ListingNotificationPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses = {ListingRepository.class, ContractRepository.class, ListingNotificationOutboxRepository.class})
    @EnableJpaAuditing
    static class Config {
        @org.springframework.context.annotation.Bean
        com.fasterxml.jackson.databind.ObjectMapper mapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        }
    }

    @Autowired ListingRepository listings;
    @Autowired ListingStatusHistoryRepository history;
    @Autowired ListingNotificationOutboxRepository outbox;
    @Autowired ListingNotificationWriter writer;
    @Autowired ListingStatusService statuses;
    @Autowired ListingService content;
    @Autowired ListingAdminService admin;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ViewingAppointmentService appointments;
    @MockitoBean MembershipService memberships;
    @MockitoBean ListingPolicyService policies;
    @MockitoBean RentalRequestService snapshots;
    @MockitoBean ListingQueryService queries;
    @MockitoBean StorageObjectRepository storageObjects;
    @MockitoBean StorageService storageService;
    @MockitoBean StorageProperties storageProperties;
    @MockitoBean UserRepository users;
    @MockitoBean com.hs.user.repository.AddressRepository addresses;
    @MockitoBean com.hs.common.location.VietmapService vietmap;
    TransactionTemplate tx;
    String listingId;

    @BeforeEach
    void fixture() {
        tx = new TransactionTemplate(manager);
        listingId = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            outbox.deleteAll(); history.deleteAll(); listings.deleteAll();
            listings.saveAndFlush(Listing.builder().id(listingId).ownerId("persisted-owner")
                    .title("Listing notification test").description("Test").category(ListingCategory.ROOM)
                    .status(ListingStatus.PENDING_REVIEW).availableFrom(LocalDate.now()).areaM2(BigDecimal.TEN)
                    .priceAmount(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH)
                    .depositType(DepositType.NONE).paymentCycle(PaymentCycle.MONTHLY).minimumLeaseMonths(1).build());
        });
        when(snapshots.buildListingSnapshotJson(any())).thenReturn("{\"listingId\":\"" + listingId + "\"}");
    }

    @Test
    void bothWriterOverloadsRequireAnExistingBusinessTransaction() {
        var listing = listings.findById(listingId).orElseThrow();
        assertThrows(IllegalTransactionStateException.class, () -> writer.enqueue(listing, "admin", LISTING_CREATED));
        assertThrows(IllegalTransactionStateException.class, () -> writer.enqueue(listing, "admin", LISTING_EDIT_APPROVED, "edit", null));
        assertEquals(0, outbox.count());
    }

    @Test
    void businessStateHistoryAndOutboxRollBackTogether() {
        tx.executeWithoutResult(status -> {
            statuses.changeByAdmin("admin", listingId, ListingStatus.PUBLISHED, null);
            assertEquals(1, outbox.count()); assertEquals(1, history.count());
            status.setRollbackOnly();
        });
        assertEquals(ListingStatus.PENDING_REVIEW, current().getStatus());
        assertEquals(0, outbox.count()); assertEquals(0, history.count());
    }

    @Test
    void outerPublicationSnapshotFailureRollsBackAlreadyEnqueuedApproval() {
        when(snapshots.buildListingSnapshotJson(any())).thenAnswer(call -> {
            assertEquals(1, outbox.count(), "approval must already be enqueued inside the outer transaction");
            return null;
        });
        assertThrows(AppException.class, () -> admin.changeStatus("admin", listingId, ListingStatus.PUBLISHED, null));
        assertEquals(ListingStatus.PENDING_REVIEW, current().getStatus());
        assertNull(current().getApprovedEditId());
        assertEquals(0, outbox.count()); assertEquals(0, history.count());
    }

    @Test
    void appointmentCancellationFailureRollsBackListingAndNotification() {
        setState(ListingStatus.PUBLISHED);
        doThrow(new IllegalStateException("test cancellation failure")).when(appointments)
                .cancelActiveAppointmentsForListing(listingId, ListingStatus.HIDDEN, "admin");
        assertThrows(IllegalStateException.class, () -> statuses.changeByAdmin("admin", listingId, ListingStatus.HIDDEN, "reason"));
        assertEquals(ListingStatus.PUBLISHED, current().getStatus());
        assertEquals(0, outbox.count()); assertEquals(0, history.count());
    }

    @Test
    void concurrentPublicationDecisionsSerializeAndOnlyTheWinnerNotifies() throws Exception {
        CountDownLatch approved = new CountDownLatch(1), release = new CountDownLatch(1), rejecting = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
                statuses.changeByAdmin("admin", listingId, ListingStatus.PUBLISHED, null);
                approved.countDown(); await(release);
            }));
            assertTrue(approved.await(10, TimeUnit.SECONDS));
            Future<?> second = pool.submit(() -> {
                rejecting.countDown();
                assertThrows(AppException.class, () -> statuses.changeByAdmin("other-admin", listingId, ListingStatus.REJECTED, "reason"));
            });
            try {
                assertTrue(rejecting.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS), "must wait for the listing row lock");
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(ListingStatus.PUBLISHED, current().getStatus());
        assertEquals(1, outbox.count()); assertEquals(1, history.count());
        assertTrue(outbox.findAll().getFirst().getPayload().contains("LISTING_APPROVED"));
    }

    @Test
    void editRejectionRestoresHiddenAndIsStaleOnRepeat() {
        String editId = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            var listing = listings.findByIdForUpdate(listingId).orElseThrow();
            listing.setPendingEditId(editId); listing.setPendingEditPayload("{\"title\":\"unapproved secret\"}");
            listing.setReviewOriginStatus(ListingStatus.HIDDEN); listing.setReviewOperationalStatus(ListingStatus.HIDDEN);
            listings.save(listing);
        });
        content.decideEdit("admin", listingId, editId, false, "reason");
        assertEquals(ListingStatus.HIDDEN, current().getStatus()); assertNull(current().getPendingEditId());
        assertThrows(AppException.class, () -> content.decideEdit("admin", listingId, editId, false, "reason"));
        assertEquals(1, outbox.count());
        var event = tools.jackson.databind.json.JsonMapper.builder().build().readTree(outbox.findAll().getFirst().getPayload());
        assertEquals("LISTING_EDIT_REJECTED", event.get("eventType").asText());
        assertEquals(editId, event.get("data").get("editId").asText());
        assertFalse(event.toString().contains("unapproved secret"));
    }

    @Test
    void expiryDuringReviewKeepsPendingEditAndNotifiesOnlyOnce() {
        tx.executeWithoutResult(status -> {
            var listing = listings.findByIdForUpdate(listingId).orElseThrow();
            listing.setReviewOperationalStatus(ListingStatus.PUBLISHED); listing.setReviewOriginStatus(ListingStatus.PUBLISHED);
            listing.setPendingEditId("pending-edit"); listing.setExpiresAt(Instant.now().minusSeconds(60));
            listings.save(listing);
        });
        assertEquals(1, statuses.expirePublishedListings(Instant.now()));
        assertEquals(0, statuses.expirePublishedListings(Instant.now()));
        assertEquals(ListingStatus.PENDING_REVIEW, current().getStatus());
        assertEquals(ListingStatus.EXPIRED, current().getOperationalStatus());
        assertEquals("pending-edit", current().getPendingEditId());
        assertEquals(1, outbox.count());
        assertTrue(outbox.findAll().getFirst().getPayload().contains("LISTING_EXPIRED"));
    }

    @Test
    void failedSendRetriesIdenticalPayloadAndPreservesPerListingOrder() {
        twoDecisions();
        var rows = rows();
        var kafka = kafka();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test broker unavailable")));
        var publisher = new ListingNotificationPublisher(jdbc, kafka, manager);
        publisher.publishPending();
        assertEquals(2, pending());
        verify(kafka).send(rows.getFirst().getTopic(), listingId, rows.getFirst().getPayload());
        clearInvocations(kafka);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publishPending();
        assertEquals(0, pending());
        var order = inOrder(kafka);
        order.verify(kafka).send(rows.getFirst().getTopic(), listingId, rows.getFirst().getPayload());
        order.verify(kafka).send(rows.getLast().getTopic(), listingId, rows.getLast().getPayload());
        order.verifyNoMoreInteractions();
        assertNotEquals(rows.getFirst().getEventId(), rows.getLast().getEventId());
        assertTrue(rows().stream().allMatch(row -> row.getSentAt() != null));
    }

    @Test
    void brokerAckBeforeDatabaseFailureRetriesTheSameIdentity() {
        statuses.changeByAdmin("admin", listingId, ListingStatus.PUBLISHED, null);
        var row = outbox.findAll().getFirst();
        var kafka = kafka();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var failingJdbc = spy(jdbc);
        doThrow(new IllegalStateException("test crash after broker ack")).when(failingJdbc).update(
                "UPDATE listing_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", row.getId());
        new ListingNotificationPublisher(failingJdbc, kafka, manager).publishPending();
        assertEquals(1, pending());
        new ListingNotificationPublisher(jdbc, kafka, manager).publishPending();
        assertEquals(0, pending());
        verify(kafka, times(2)).send(row.getTopic(), listingId, row.getPayload());
        assertEquals(row.getEventId(), outbox.findById(row.getId()).orElseThrow().getEventId());
    }

    @Test
    void concurrentPublisherSkipsLockedHeadWithoutOvertakingTheSameListing() throws Exception {
        twoDecisions();
        String otherListing = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> writer.enqueue(Listing.builder().id(otherListing).ownerId("another-owner").build(), "admin", LISTING_CREATED));
        var first = kafka(); var second = kafka();
        CountDownLatch claimed = new CountDownLatch(1);
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> acknowledgement = new CompletableFuture<>();
        when(first.send(anyString(), anyString(), anyString())).thenAnswer(call -> { claimed.countDown(); return acknowledgement; });
        when(second.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> worker = pool.submit(() -> new ListingNotificationPublisher(jdbc, first, manager).publishPending());
            try {
                assertTrue(claimed.await(10, TimeUnit.SECONDS));
                assertEquals(3, pending(), "broker acknowledgement is required before sent_at changes");
                new ListingNotificationPublisher(jdbc, second, manager).publishPending();
                verify(second).send(anyString(), eq(otherListing), anyString());
                verify(second, never()).send(anyString(), eq(listingId), anyString());
            } finally { acknowledgement.complete(null); }
            worker.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, pending()); verify(first, times(2)).send(anyString(), eq(listingId), anyString());
    }

    private Listing current() { return listings.findById(listingId).orElseThrow(); }
    private void setState(ListingStatus value) {
        tx.executeWithoutResult(status -> { var listing = listings.findByIdForUpdate(listingId).orElseThrow(); listing.setStatus(value); listings.save(listing); });
    }
    private void twoDecisions() {
        statuses.changeByAdmin("admin", listingId, ListingStatus.PUBLISHED, null);
        statuses.changeByAdmin("admin", listingId, ListingStatus.HIDDEN, "reason");
    }
    private List<ListingNotificationOutbox> rows() { return outbox.findAll().stream().sorted(Comparator.comparing(ListingNotificationOutbox::getId)).toList(); }
    private long pending() { return jdbc.queryForObject("SELECT COUNT(*) FROM listing_notification_outbox WHERE sent_at IS NULL", Long.class); }
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafka() { return mock(KafkaTemplate.class); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out awaiting test transaction"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
