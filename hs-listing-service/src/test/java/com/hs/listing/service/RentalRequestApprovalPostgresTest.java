package com.hs.listing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.estimate.RentalEstimateResponse;
import com.hs.listing.dto.request.CreateRentalRequest;
import com.hs.listing.model.Listing;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.*;
import com.hs.listing.notification.RentalRequestNotificationOutbox;
import com.hs.listing.notification.RentalRequestNotificationOutboxRepository;
import com.hs.listing.notification.RentalRequestNotificationWriter;
import com.hs.listing.port.RentalPaymentLifecyclePort;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in: use HS_TEST_PG_URL pointing ONLY to a throwaway database (create-drop schema). */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL", matches = ".+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}",
        "spring.datasource.username=${HS_TEST_PG_USER:postgres}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD:test}",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RentalRequestService.class, RentalRequestNotificationWriter.class})
class RentalRequestApprovalPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses = {RentalRequestRepository.class, ListingRepository.class, RentalRequestNotificationOutboxRepository.class})
    @EnableJpaAuditing
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    @Autowired RentalRequestService service;
    @Autowired ListingRepository listings;
    @Autowired RentalRequestRepository requests;
    @Autowired RentalRequestNotificationOutboxRepository outbox;
    @MockitoBean RentalCostCalculator calculator;
    @MockitoBean ParkingReservationService parking;
    @MockitoBean ListingStatusService listingStatus;
    @Autowired PropertyBranchRepository branches;
    @MockitoBean RentalPaymentLifecyclePort payments;
    @MockitoBean StorageProperties storage;
    @MockitoBean BillingTime clock;

    record Fixture(String listingId, String firstId, String secondId) {}

    @BeforeEach
    void setup() {
        outbox.deleteAllInBatch();
        requests.deleteAllInBatch();
        listings.deleteAllInBatch();
        branches.deleteAllInBatch();
        when(clock.now()).thenReturn(NOW);
        when(calculator.calculate(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), nullable(BigDecimal.class)))
                .thenReturn(RentalEstimateResponse.builder()
                        .effectiveMonthlyRent(new BigDecimal("5000000")).depositAmount(new BigDecimal("5000000"))
                        .predictableCharges(List.of()).excludedCharges(List.of())
                        .predictableMonthlyChargesTotal(BigDecimal.ZERO).estimatedMonthlyTotal(new BigDecimal("5000000"))
                        .estimatedInitialTotal(new BigDecimal("10000000")).estimatedLeaseTotal(new BigDecimal("30000000"))
                        .build());
        doAnswer(call -> {
            Listing listing = call.getArgument(0);
            listing.setStatus(ListingStatus.RESERVED);
            return null;
        }).when(listingStatus).markReserved(any(), eq("owner"));
    }

    private Fixture fixture(boolean inBranch) {
        String branchId = inBranch ? branches.saveAndFlush(PropertyBranch.builder()
                .id(UUID.randomUUID().toString()).ownerId("owner").name("Tòa nhà kiểm thử")
                .category(ListingCategory.ROOM).build()).getId() : null;
        var listing = listings.saveAndFlush(Listing.builder().id(UUID.randomUUID().toString()).ownerId("owner")
                .title("Phòng kiểm thử duyệt yêu cầu").description("Dữ liệu kiểm thử")
                .category(ListingCategory.ROOM).status(ListingStatus.PUBLISHED).availableFrom(TODAY)
                .areaM2(BigDecimal.TEN).priceAmount(new BigDecimal("5000000")).currency("VND")
                .priceUnit(PriceUnit.MONTH).depositType(DepositType.MONTH_COUNT).depositMonths(1)
                .minimumLeaseMonths(1).paymentCycle(PaymentCycle.MONTHLY).branchId(branchId).build());
        var first = saveRequest(listing, RentalRequestStatus.PENDING, "tenant-1");
        var second = saveRequest(listing, RentalRequestStatus.PENDING, "tenant-2");
        return new Fixture(listing.getId(), first.getId(), second.getId());
    }

    private RentalRequest saveRequest(Listing listing, RentalRequestStatus status, String renter) {
        return requests.saveAndFlush(RentalRequest.builder().id(UUID.randomUUID().toString()).listing(listing)
                .ownerId("owner").renterId(renter).renterName(renter).renterPhone("0900000000")
                .status(status).moveInDate(TODAY.plusDays(1)).leaseMonths(6)
                .monthlyRentPrice(new BigDecimal("5000000")).depositAmount(new BigDecimal("5000000"))
                .respondBy(NOW.plusSeconds(86400)).build());
    }

    @Test
    void acceptanceRejectsEveryCompetingPendingRequestWithAReasonAndLeavesOtherListingsAndHistoryUntouched() {
        var selected = fixture(false);
        var otherListing = fixture(false);
        var listing = listings.findById(selected.listingId()).orElseThrow();
        for (int i = 0; i < 12; i++) saveRequest(listing, RentalRequestStatus.PENDING, "extra-" + i);
        var historical = saveRequest(listing, RentalRequestStatus.REJECTED, "previous");
        historical.setRejectReason("Lý do từ chối trước đó");
        requests.saveAndFlush(historical);
        var inactive = saveRequest(listing, RentalRequestStatus.PENDING, "inactive");
        inactive.setActive(false);
        requests.saveAndFlush(inactive);

        service.acceptRentalRequest("owner", selected.firstId());

        assertEquals(RentalRequestStatus.ACCEPTED, requests.findById(selected.firstId()).orElseThrow().getStatus());
        var rejected = requests.findByListingIdAndStatus(selected.listingId(), RentalRequestStatus.REJECTED);
        assertEquals(14, rejected.size()); // 13 competing requests + the historical rejection.
        for (var row : rejected) {
            if (row.getId().equals(historical.getId())) {
                assertEquals("Lý do từ chối trước đó", row.getRejectReason());
            } else {
                assertEquals("Chủ nhà đã chấp thuận yêu cầu thuê khác cho tin đăng này.", row.getRejectReason());
                assertEquals("owner", row.getUpdatedBy());
                assertEquals(NOW, row.getUpdatedAt());
            }
        }
        assertEquals(RentalRequestStatus.PENDING, requests.findById(inactive.getId()).orElseThrow().getStatus());
        assertEquals(2, requests.findByListingIdAndStatus(otherListing.listingId(), RentalRequestStatus.PENDING).size());
        assertEquals(ListingStatus.RESERVED, listings.findById(selected.listingId()).orElseThrow().getStatus());
        verify(payments, times(1)).createInitialPayment(any(), any());

        // Exactly one ACCEPTED (renter) plus one AUTO_REJECTED per really-rejected contender; nothing for
        // the inactive, historical or other-listing rows.
        var expectedContenders = new java.util.TreeSet<String>();
        for (var row : rejected) if (!row.getId().equals(historical.getId())) expectedContenders.add(row.getId() + "|" + row.getRenterId());
        assertEquals(13, expectedContenders.size());
        var seen = new java.util.TreeSet<String>();
        int accepted = 0;
        for (var row : outbox.findAll()) {
            var event = json.readTree(row.getPayload());
            var data = event.get("data");
            assertEquals(row.getRentalRequestId(), data.get("requestId").asText());
            assertEquals("RENTER", data.get("recipientSide").asText());
            assertEquals(1, event.get("recipients").size());
            assertEquals(java.util.Set.of("requestId", "recipientSide", "listingTitle"), fieldNames(data));
            if ("RENTAL_REQUEST_ACCEPTED".equals(event.get("eventType").asText())) {
                accepted++;
                assertEquals(selected.firstId(), row.getRentalRequestId());
                assertEquals("tenant-1", event.get("recipients").get(0).asText());
            } else {
                assertEquals("RENTAL_REQUEST_AUTO_REJECTED", event.get("eventType").asText());
                seen.add(row.getRentalRequestId() + "|" + event.get("recipients").get(0).asText());
            }
        }
        assertEquals(1, accepted);
        assertEquals(expectedContenders, seen);
        assertEquals(14, outbox.count());
        assertFalse(seen.stream().anyMatch(v -> v.startsWith(inactive.getId()) || v.startsWith(historical.getId())));
        assertTrue(requests.findByListingIdAndStatus(otherListing.listingId(), RentalRequestStatus.PENDING).stream()
                .noneMatch(r -> seen.stream().anyMatch(v -> v.startsWith(r.getId()))));
    }

    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();

    private static java.util.Set<String> fieldNames(tools.jackson.databind.JsonNode node) {
        var names = new java.util.TreeSet<String>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private List<String> eventTypes() {
        return outbox.findAll().stream().map(r -> json.readTree(r.getPayload()).get("eventType").asText()).sorted().toList();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentApprovalsProduceExactlyOneAcceptedRequestAndOnePayment(boolean inBranch) throws Exception {
        var data = fixture(inBranch);

        var outcomes = race(() -> accept(data.firstId()), () -> accept(data.secondId()));

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.ACCEPTED).size());
        assertEquals(1, requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.REJECTED).size());
        assertTrue(requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.PENDING).isEmpty());
        verify(payments, times(1)).createInitialPayment(any(), any());
        verify(parking, times(1)).createHeldReservations(any(), any(), any());
        // One winner notice and one auto-reject for the loser; the loser never gets ACCEPTED.
        assertEquals(List.of("RENTAL_REQUEST_ACCEPTED", "RENTAL_REQUEST_AUTO_REJECTED"), eventTypes());
        String winner = requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.ACCEPTED).getFirst().getId();
        for (var row : outbox.findAll()) {
            boolean acceptedEvent = row.getPayload().contains("\"RENTAL_REQUEST_ACCEPTED\"");
            assertEquals(acceptedEvent, winner.equals(row.getRentalRequestId()));
        }
    }

    private boolean accept(String requestId) {
        try {
            service.acceptRentalRequest("owner", requestId);
            return true;
        } catch (AppException error) {
            assertTrue(error.getCode() == ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS.getCode()
                    || error.getCode() == ListingErrorCode.LISTING_ALREADY_RESERVED.getCode());
            return false;
        }
    }

    @Test
    void paymentCreationFailureRollsBackTheAcceptanceAndKeepsCompetitorsPending() {
        var data = fixture(false);
        doThrow(new IllegalStateException("Cannot create payment"))
                .when(payments).createInitialPayment(any(), any());

        assertThrows(IllegalStateException.class, () -> service.acceptRentalRequest("owner", data.firstId()));

        assertEquals(2, requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.PENDING).size());
        assertTrue(requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.REJECTED).isEmpty());
        assertEquals(ListingStatus.PUBLISHED, listings.findById(data.listingId()).orElseThrow().getStatus());
        assertEquals(0, outbox.count());
    }

    @Test
    void parkingFailureRollsBackAcceptanceContenderRejectionAndOutboxTogether() {
        var data = fixture(false);
        doThrow(new IllegalStateException("Cannot hold parking"))
                .when(parking).createHeldReservations(any(), any(), any());

        assertThrows(IllegalStateException.class, () -> service.acceptRentalRequest("owner", data.firstId()));

        assertEquals(2, requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.PENDING).size());
        assertTrue(requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.ACCEPTED).isEmpty());
        assertEquals(0, outbox.count());
    }

    @Test
    void submitRejectAndRenterCancelEachWriteOneSideSpecificDurableEvent() {
        var data = fixture(false);
        var listing = listings.findById(data.listingId()).orElseThrow();
        var created = service.createRentalRequest("late-tenant", null, new CreateRentalRequest(
                data.listingId(), TODAY.plusDays(1), 6, 1, 0, 0, "Khách mới", "0900000000", null, null, null, true));
        service.rejectRentalRequest("owner", data.secondId(), new com.hs.listing.dto.request.RejectRentalRequest("Lý do riêng tư"));
        var cancellable = saveRequest(listing, RentalRequestStatus.PENDING, "tenant-cancel");
        service.cancelRentalRequest("tenant-cancel", cancellable.getId());
        assertThrows(AppException.class, () -> service.cancelRentalRequest("tenant-cancel", cancellable.getId()));
        assertThrows(AppException.class, () -> service.rejectRentalRequest("intruder", data.firstId(), null));
        var rows = outbox.findAll().stream().sorted(java.util.Comparator.comparing(RentalRequestNotificationOutbox::getId)).toList();
        assertEquals(3, rows.size());
        var submitted = json.readTree(rows.get(0).getPayload());
        assertEquals("RENTAL_REQUEST_SUBMITTED", submitted.get("eventType").asText());
        assertEquals("OWNER", submitted.get("data").get("recipientSide").asText());
        assertEquals("owner", submitted.get("recipients").get(0).asText());
        assertEquals(created.id(), rows.get(0).getRentalRequestId());
        var rejectedEvent = json.readTree(rows.get(1).getPayload());
        assertEquals("RENTAL_REQUEST_REJECTED", rejectedEvent.get("eventType").asText());
        assertEquals("tenant-2", rejectedEvent.get("recipients").get(0).asText());
        assertFalse(rows.get(1).getPayload().contains("riêng tư"));
        var cancelled = json.readTree(rows.get(2).getPayload());
        assertEquals("RENTAL_REQUEST_CANCELLED", cancelled.get("eventType").asText());
        assertEquals("OWNER", cancelled.get("data").get("recipientSide").asText());
        assertEquals("owner", cancelled.get("recipients").get(0).asText());
    }

    @Test
    void submissionRacingWithAcceptanceCannotLeaveANewPendingRequestBehind() throws Exception {
        var data = fixture(false);
        race(() -> accept(data.firstId()), () -> {
            try {
                service.createRentalRequest("late-tenant", null, new CreateRentalRequest(
                        data.listingId(), TODAY.plusDays(1), 6, 1, 0, 0, "Khách mới", "0900000000", null, null, null, true));
                return true;
            } catch (AppException error) {
                assertEquals(ListingErrorCode.LISTING_ALREADY_RESERVED.getCode(), error.getCode());
                return false;
            }
        });

        assertTrue(requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.PENDING).isEmpty());
        assertEquals(1, requests.findByListingIdAndStatus(data.listingId(), RentalRequestStatus.ACCEPTED).size());
    }

    @Test
    void acceptRacingWithRenterCancelNotifiesOnlyTheWinningTransition() throws Exception {
        var data = fixture(false);
        var outcomes = race(() -> accept(data.firstId()), () -> {
            try {
                service.cancelRentalRequest("tenant-1", data.firstId());
                return true;
            } catch (AppException error) {
                assertEquals(ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS.getCode(), error.getCode());
                return false;
            }
        });
        var first = requests.findById(data.firstId()).orElseThrow();
        var types = eventTypes();
        if (first.getStatus() == RentalRequestStatus.ACCEPTED) {
            assertEquals(List.of("RENTAL_REQUEST_ACCEPTED", "RENTAL_REQUEST_AUTO_REJECTED"), types);
            assertEquals(true, outcomes.getFirst());
            assertEquals(false, outcomes.getLast());
        } else {
            assertEquals(RentalRequestStatus.CANCELLED_BY_RENTER, first.getStatus());
            // Cancel won: the owner is told; the second request stays unaffected unless accepted afterwards.
            assertEquals(List.of("RENTAL_REQUEST_CANCELLED"), types);
            assertEquals(RentalRequestStatus.PENDING, requests.findById(data.secondId()).orElseThrow().getStatus());
        }
    }

    @Test
    void responseExpiryRacingWithRenterCancelProducesExactlyOneTerminalNotification() throws Exception {
        var data = fixture(false);
        var request = requests.findById(data.firstId()).orElseThrow();
        request.setRespondBy(NOW.minusSeconds(5));
        requests.saveAndFlush(request);
        race(() -> {
            service.expirePendingHoldRequests(NOW);
            return true;
        }, () -> {
            try {
                service.cancelRentalRequest("tenant-1", data.firstId());
            } catch (AppException error) {
                assertEquals(ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS.getCode(), error.getCode());
            }
            return true;
        });
        var status = requests.findById(data.firstId()).orElseThrow().getStatus();
        if (status == RentalRequestStatus.EXPIRED) {
            assertEquals(List.of("RENTAL_REQUEST_EXPIRED", "RENTAL_REQUEST_EXPIRED"), eventTypes());
            var recipients = outbox.findAll().stream()
                    .map(r -> json.readTree(r.getPayload()).get("recipients").get(0).asText()).sorted().toList();
            assertEquals(List.of("owner", "tenant-1"), recipients);
        } else {
            assertEquals(RentalRequestStatus.CANCELLED_BY_RENTER, status);
            assertEquals(List.of("RENTAL_REQUEST_CANCELLED"), eventTypes());
        }
    }

    @Test
    void overdueRequestCannotBeAcceptedWhileResponseExpiryIsRunning() throws Exception {
        var data = fixture(false);
        var request = requests.findById(data.firstId()).orElseThrow();
        request.setRespondBy(NOW.minusSeconds(5));
        requests.saveAndFlush(request);
        race(() -> {
            try {
                service.acceptRentalRequest("owner", data.firstId());
                fail("An overdue response must not be accepted");
            } catch (AppException expected) {
                // The expiry job may already have committed, or accept observes the overdue PENDING row.
            }
            return true;
        }, () -> {
            service.expirePendingHoldRequests(NOW);
            return true;
        });
        assertEquals(RentalRequestStatus.EXPIRED, requests.findById(data.firstId()).orElseThrow().getStatus());
        assertEquals(RentalRequestStatus.PENDING, requests.findById(data.secondId()).orElseThrow().getStatus());
        assertEquals(List.of("RENTAL_REQUEST_EXPIRED", "RENTAL_REQUEST_EXPIRED"), eventTypes());
        var recipients = outbox.findAll().stream()
                .map(r -> json.readTree(r.getPayload()).get("recipients").get(0).asText()).sorted().toList();
        assertEquals(List.of("owner", "tenant-1"), recipients);
        verify(payments, never()).createInitialPayment(any(), any());
    }

    @Test
    void holdExpiryNotifiesBothPartiesOnceAndProtectedOrAlreadyTerminalRequestsStaySilent() {
        var data = fixture(false);
        service.acceptRentalRequest("owner", data.firstId());
        outbox.deleteAllInBatch();
        var accepted = requests.findById(data.firstId()).orElseThrow();
        accepted.setHoldExpiresAt(NOW.minusSeconds(5));
        requests.saveAndFlush(accepted);
        when(payments.isHoldProtected(data.firstId())).thenReturn(true);
        assertEquals(0, service.expirePendingHoldRequests(NOW));
        assertEquals(0, outbox.count());
        when(payments.isHoldProtected(data.firstId())).thenReturn(false);
        assertEquals(1, service.expirePendingHoldRequests(NOW));
        assertEquals(0, service.expirePendingHoldRequests(NOW));
        assertEquals(List.of("RENTAL_HOLD_EXPIRED", "RENTAL_HOLD_EXPIRED"), eventTypes());
        var recipients = outbox.findAll().stream()
                .map(r -> json.readTree(r.getPayload()).get("recipients").get(0).asText()).sorted().toList();
        assertEquals(List.of("owner", "tenant-1"), recipients);
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return action.call();
            })).toList();
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }
}
