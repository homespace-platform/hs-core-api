package com.hs.listing.notification;

import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static com.hs.listing.notification.ListingNotificationWriter.Event.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingNotificationWriterTest {
    private final ListingNotificationOutboxRepository outbox = mock(ListingNotificationOutboxRepository.class);
    private final ListingNotificationWriter writer = new ListingNotificationWriter(outbox, "test.notifications");
    private final JsonMapper json = JsonMapper.builder().build();

    private Listing listing() {
        return Listing.builder().id("11111111-1111-4111-8111-111111111111").ownerId("persisted-owner")
                .title("  Phòng <đẹp>\n\ttrung tâm\u0001  ").pendingEditPayload("unapproved secret content")
                .description("private description").statusReason("private reason").build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportsAllEightRealJavaPayloadsWithTheExactV1SchemaAndNewDecisionIdentities() throws Exception {
        List<Map<String, Object>> fixtures = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (var type : ListingNotificationWriter.Event.values()) {
            boolean edit = type == LISTING_EDIT_APPROVED || type == LISTING_EDIT_REJECTED;
            writer.enqueue(listing(), "admin", type, edit ? "22222222-2222-4222-8222-222222222222" : null,
                    type == LISTING_STATUS_CHANGED ? ListingStatus.VIOLATION : null);
        }
        var captured = ArgumentCaptor.forClass(ListingNotificationOutbox.class);
        verify(outbox, times(8)).save(captured.capture());
        for (var row : captured.getAllValues()) {
            Map<String, Object> event = json.readValue(row.getPayload(), Map.class);
            assertEquals(Set.of("eventId", "eventType", "version", "occurredAt", "recipients", "data"), event.keySet());
            assertEquals(1, event.get("version"));
            assertEquals(List.of("persisted-owner"), event.get("recipients"));
            assertEquals(row.getEventId(), event.get("eventId"));
            assertEquals(row.getEventId(), UUID.fromString(row.getEventId()).toString());
            assertTrue(identities.add(row.getEventId()), "each decision needs a new event identity");
            String timestamp = (String) event.get("occurredAt");
            assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
            assertEquals(row.getCreatedAt().toEpochMilli(), Instant.parse(timestamp).toEpochMilli());
            Map<String, String> data = (Map<String, String>) event.get("data");
            String type = (String) event.get("eventType");
            Set<String> expectedKeys = new HashSet<>(Set.of("listingId", "listingTitle"));
            if (type.startsWith("LISTING_EDIT_")) {
                expectedKeys.add("editId");
                assertEquals("22222222-2222-4222-8222-222222222222", data.get("editId"));
            }
            if (type.equals("LISTING_STATUS_CHANGED")) {
                expectedKeys.add("status"); assertEquals("VIOLATION", data.get("status"));
            }
            assertEquals(expectedKeys, data.keySet());
            assertEquals(row.getListingId(), data.get("listingId"));
            assertEquals("Phòng đẹp trung tâm", data.get("listingTitle"));
            assertEquals("test.notifications", row.getTopic());
            assertNull(row.getSentAt());
            assertFalse(row.getPayload().contains("secret"));
            assertFalse(row.getPayload().contains("private"));
            fixtures.add(event);
        }
        Path fixture = Path.of("target", "listing-event-contracts.json");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, json.writeValueAsString(fixtures));
    }

    @Test
    void separateDecisionsOfTheSameTypeHaveDistinctIdentities() {
        writer.enqueue(listing(), "admin", LISTING_UPDATED);
        writer.enqueue(listing(), "admin", LISTING_UPDATED);
        var rows = ArgumentCaptor.forClass(ListingNotificationOutbox.class);
        verify(outbox, times(2)).save(rows.capture());
        assertNotEquals(rows.getAllValues().getFirst().getEventId(), rows.getAllValues().getLast().getEventId());
    }

    @ParameterizedTest
    @EnumSource(value = ListingStatus.class, names = {"PENDING_REVIEW", "HIDDEN", "VIOLATION", "RENTED_EXTERNALLY"})
    void serializesOnlySupportedStatusChangedValues(ListingStatus status) {
        writer.enqueue(listing(), "admin", LISTING_STATUS_CHANGED, null, status);
        var row = ArgumentCaptor.forClass(ListingNotificationOutbox.class);
        verify(outbox).save(row.capture());
        assertEquals(status.name(), json.readTree(row.getValue().getPayload()).get("data").get("status").asText());
    }

    @Test
    void missingStatusUsesTheValidationExceptionInsteadOfNullPointerException() {
        assertThrows(IllegalStateException.class, () -> writer.enqueue(listing(), "admin", LISTING_STATUS_CHANGED, null, null));
        verifyNoInteractions(outbox);
    }

    @ParameterizedTest
    @EnumSource(value = ListingStatus.class, names = {"PUBLISHED", "REJECTED", "DRAFT", "EXPIRED", "RESERVED", "RENTED"})
    void unsupportedStatusChangedValuesAreRejected(ListingStatus status) {
        assertThrows(IllegalStateException.class, () -> writer.enqueue(listing(), "admin", LISTING_STATUS_CHANGED, null, status));
        verifyNoInteractions(outbox);
    }

    @Test
    void decisionContextCannotLeakIntoTheWrongEventShape() {
        assertThrows(IllegalStateException.class, () -> writer.enqueue(listing(), "admin", LISTING_EDIT_APPROVED));
        assertThrows(IllegalStateException.class, () -> writer.enqueue(listing(), "admin", LISTING_EDIT_REJECTED, " ", null));
        assertThrows(IllegalStateException.class, () -> writer.enqueue(listing(), "admin", LISTING_APPROVED, "edit", null));
        assertThrows(IllegalStateException.class, () -> writer.enqueue(listing(), "admin", LISTING_EXPIRED, null, ListingStatus.HIDDEN));
        verifyNoInteractions(outbox);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void missingPersistedOwnerOrListingNeverQueuesAnEvent(String missing) {
        var listing = listing(); listing.setOwnerId(missing);
        assertThrows(IllegalStateException.class, () -> writer.enqueue(listing, "admin", LISTING_CREATED));
        listing.setOwnerId("persisted-owner"); listing.setId(missing);
        assertThrows(IllegalStateException.class, () -> writer.enqueue(listing, "admin", LISTING_CREATED));
        verifyNoInteractions(outbox);
    }

    @Test
    void suppressesAnAdminActingOnTheirOwnListing() {
        writer.enqueue(listing(), "persisted-owner", LISTING_CREATED);
        verifyNoInteractions(outbox);
    }

    @Test
    void capsTitleAndOmitsBlankTitle() {
        var listing = listing(); listing.setTitle("x".repeat(150));
        writer.enqueue(listing, "admin", LISTING_REJECTED);
        listing.setTitle(" <>\n\t "); writer.enqueue(listing, "admin", LISTING_REJECTED);
        var rows = ArgumentCaptor.forClass(ListingNotificationOutbox.class);
        verify(outbox, times(2)).save(rows.capture());
        assertEquals(120, json.readTree(rows.getAllValues().getFirst().getPayload()).get("data").get("listingTitle").asText().length());
        assertFalse(json.readTree(rows.getAllValues().getLast().getPayload()).get("data").has("listingTitle"));
    }
}
