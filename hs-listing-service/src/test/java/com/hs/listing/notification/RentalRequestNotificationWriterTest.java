package com.hs.listing.notification;

import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static com.hs.listing.notification.RentalRequestNotificationWriter.Event.*;
import static com.hs.listing.notification.RentalRequestNotificationWriter.Side.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RentalRequestNotificationWriterTest {
    private final RentalRequestNotificationOutboxRepository rows = mock(RentalRequestNotificationOutboxRepository.class);
    private final RentalRequestNotificationWriter writer = new RentalRequestNotificationWriter(rows, "test.rental.notifications");
    private final JsonMapper json = JsonMapper.builder().build();

    private RentalRequest request() {
        return RentalRequest.builder().id("11111111-1111-4111-8111-111111111111")
                .ownerId("persisted-owner").renterId("persisted-renter")
                .listing(Listing.builder().id("listing-other-id").ownerId("not-the-request-owner")
                        .title("  Phòng <đẹp>\ntrung tâm ").pendingEditPayload("private proposed content").build())
                .rejectReason("private rejection reason").build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyEventAndRecipientSideUsesExactSafeEnvelopeAndExportsRealJavaFixtures() throws Exception {
        var fixtures = new ArrayList<Map<String, Object>>();
        for (var type : RentalRequestNotificationWriter.Event.values()) {
            writer.enqueue(request(), type, OWNER);
            writer.enqueue(request(), type, RENTER);
        }
        var captured = ArgumentCaptor.forClass(RentalRequestNotificationOutbox.class);
        verify(rows, times(52)).save(captured.capture());
        var identities = new HashSet<String>();
        for (var row : captured.getAllValues()) {
            Map<String, Object> event = json.readValue(row.getPayload(), Map.class);
            assertEquals(Set.of("eventId", "eventType", "version", "occurredAt", "recipients", "data"), event.keySet());
            assertEquals(row.getEventId(), event.get("eventId")); assertTrue(identities.add(row.getEventId()));
            assertEquals(1, event.get("version"));
            assertTrue(((String) event.get("occurredAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
            assertDoesNotThrow(() -> Instant.parse((String) event.get("occurredAt")));
            var data = (Map<String, String>) event.get("data");
            assertEquals(Set.of("requestId", "recipientSide", "listingTitle"), data.keySet());
            assertEquals(row.getRentalRequestId(), data.get("requestId"));
            assertEquals("Phòng đẹp trung tâm", data.get("listingTitle"));
            assertEquals(List.of("OWNER".equals(data.get("recipientSide")) ? "persisted-owner" : "persisted-renter"), event.get("recipients"));
            assertEquals("test.rental.notifications", row.getTopic()); assertNull(row.getSentAt());
            assertFalse(row.getPayload().contains("private")); assertFalse(row.getPayload().contains("not-the-request-owner"));
            fixtures.add(event);
        }
        Path path = Path.of("target", "rental-request-event-contracts.json");
        Files.createDirectories(path.getParent()); Files.writeString(path, json.writeValueAsString(fixtures));
    }

    @Test
    void actorSuppressionUsesPersistedPartiesAndSystemOrAdminReachesBoth() {
        writer.enqueueOthers(request(), "persisted-renter", RENTAL_REQUEST_CANCELLED);
        writer.enqueueOthers(request(), "persisted-owner", RENTAL_CANCELLATION_REQUESTED);
        writer.enqueueOthers(request(), "SYSTEM", RENTAL_REQUEST_EXPIRED);
        writer.enqueueOthers(request(), "admin", RENTAL_HOLD_EXTENDED);
        var captured = ArgumentCaptor.forClass(RentalRequestNotificationOutbox.class);
        verify(rows, times(6)).save(captured.capture());
        var recipients = captured.getAllValues().stream().map(row ->
                json.readTree(row.getPayload()).get("recipients").get(0).asText()).toList();
        assertEquals(List.of("persisted-owner", "persisted-renter", "persisted-owner", "persisted-renter", "persisted-owner", "persisted-renter"), recipients);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"  "})
    void missingActorRequestOrRecipientFailsBeforeEnqueue(String missing) {
        assertThrows(IllegalStateException.class, () -> writer.enqueueOthers(request(), missing, RENTAL_REQUEST_CANCELLED));
        var r = request(); r.setId(missing);
        assertThrows(IllegalStateException.class, () -> writer.enqueue(r, RENTAL_REQUEST_SUBMITTED, OWNER));
        r.setId("valid-id"); r.setOwnerId(missing);
        assertThrows(IllegalStateException.class, () -> writer.enqueue(r, RENTAL_REQUEST_SUBMITTED, OWNER));
        r.setOwnerId("owner"); r.setRenterId(missing);
        assertThrows(IllegalStateException.class, () -> writer.enqueue(r, RENTAL_REQUEST_ACCEPTED, RENTER));
        verifyNoInteractions(rows);
    }

    @Test
    void optionalTitleIsBoundedAndBlankOrMissingTitlesAreOmitted() {
        var r = request(); r.getListing().setTitle("x".repeat(150));
        writer.enqueue(r, RENTAL_START_CHANGE_REQUESTED, OWNER);
        r.getListing().setTitle(" <>\t\n "); writer.enqueue(r, RENTAL_START_CHANGE_REQUESTED, OWNER);
        r.setListing(null); writer.enqueue(r, RENTAL_START_CHANGE_REQUESTED, OWNER);
        var captured = ArgumentCaptor.forClass(RentalRequestNotificationOutbox.class);
        verify(rows, times(3)).save(captured.capture());
        assertEquals(120, json.readTree(captured.getAllValues().getFirst().getPayload()).get("data").get("listingTitle").asText().length());
        assertFalse(json.readTree(captured.getAllValues().get(1).getPayload()).get("data").has("listingTitle"));
        assertFalse(json.readTree(captured.getAllValues().getLast().getPayload()).get("data").has("listingTitle"));
    }
}
