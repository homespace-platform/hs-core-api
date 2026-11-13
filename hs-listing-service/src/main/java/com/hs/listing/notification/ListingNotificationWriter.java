package com.hs.listing.notification;

import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Enqueues v1 listing notifications for the persisted owner. Callers decide explicitly which event a committed
 * decision produces; this class never infers events from status changes.
 */
@Service
public class ListingNotificationWriter {
    public enum Event {
        LISTING_CREATED, LISTING_UPDATED, LISTING_APPROVED, LISTING_REJECTED,
        LISTING_EDIT_APPROVED, LISTING_EDIT_REJECTED, LISTING_STATUS_CHANGED, LISTING_EXPIRED
    }
    private static final Set<ListingStatus> STATUS_CHANGED_VALUES = Set.of(ListingStatus.PENDING_REVIEW,
            ListingStatus.HIDDEN, ListingStatus.VIOLATION, ListingStatus.RENTED_EXTERNALLY);
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final JsonMapper json = JsonMapper.builder().build();
    private final ListingNotificationOutboxRepository outbox;
    private final String topic;

    public ListingNotificationWriter(ListingNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    /** CREATED, UPDATED, APPROVED, REJECTED or EXPIRED. Annotated too: the self-call below bypasses the proxy. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Listing listing, String actorId, Event type) {
        enqueue(listing, actorId, type, null, null);
    }

    /** editId is required for EDIT_APPROVED/EDIT_REJECTED and status for STATUS_CHANGED; both are forbidden otherwise. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Listing listing, String actorId, Event type, String editId, ListingStatus status) {
        boolean edit = type == Event.LISTING_EDIT_APPROVED || type == Event.LISTING_EDIT_REJECTED;
        if (edit ? editId == null || editId.isBlank() : editId != null)
            throw new IllegalStateException("Listing notification editId mismatch");
        if (type == Event.LISTING_STATUS_CHANGED ? status == null || !STATUS_CHANGED_VALUES.contains(status) : status != null)
            throw new IllegalStateException("Listing notification status mismatch");
        if (listing.getId() == null || listing.getId().isBlank())
            throw new IllegalStateException("Listing notification listing missing");
        String recipient = listing.getOwnerId();
        if (recipient == null || recipient.isBlank()) throw new IllegalStateException("Listing owner missing");
        if (recipient.equals(actorId)) return; // the owner already knows about their own admin action
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("listingId", listing.getId());
        if (listing.getTitle() != null) {
            String title = listing.getTitle().replaceAll("[<>\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
            if (!title.isEmpty()) data.put("listingTitle", title.substring(0, Math.min(title.length(), 120)).trim());
        }
        if (edit) data.put("editId", editId);
        if (status != null) data.put("status", status.name());
        String payload = json.writeValueAsString(Map.of(
                "eventId", eventId, "eventType", type.name(), "version", 1,
                "occurredAt", TIMESTAMP.format(now), "recipients", List.of(recipient),
                "data", data));
        outbox.save(new ListingNotificationOutbox(eventId, listing.getId(), topic, payload, now));
    }
}
