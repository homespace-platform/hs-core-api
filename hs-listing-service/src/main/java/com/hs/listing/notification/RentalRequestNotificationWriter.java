package com.hs.listing.notification;

import com.hs.listing.model.RentalRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable rental-request notifications. Payload is frozen at enqueue time and carries no reason, money,
 * names, dates or URLs: only requestId, recipientSide and the sanitized current listing title.
 */
@Service
public class RentalRequestNotificationWriter {
    public enum Side { OWNER, RENTER }
    public enum Event {
        RENTAL_REQUEST_SUBMITTED, RENTAL_REQUEST_ACCEPTED, RENTAL_REQUEST_REJECTED, RENTAL_REQUEST_AUTO_REJECTED,
        RENTAL_REQUEST_CANCELLED, RENTAL_REQUEST_EXPIRED, RENTAL_HOLD_EXPIRED,
        RENTAL_CANCELLATION_REQUESTED, RENTAL_CANCELLATION_REFUND_PENDING, RENTAL_CANCELLATION_REJECTED,
        RENTAL_CANCELLATION_WITHDRAWN, RENTAL_CANCELLATION_DISPUTED,
        RENTAL_START_CHANGE_REQUESTED, RENTAL_START_CHANGE_APPROVED, RENTAL_START_CHANGE_REJECTED,
        RENTAL_START_CHANGE_WITHDRAWN, RENTAL_START_CHANGE_EXPIRED,
        RENTAL_SIGNING_WINDOW_REOPENED, RENTAL_HOLD_EXTENDED,
        RENTAL_DEFAULT_NOTICE_ISSUED, RENTAL_DEFAULT_DISPUTED, RENTAL_REQUEST_CANCELLED_BY_SYSTEM,
        RENTAL_RESUME_REQUESTED, RENTAL_RESUMED, RENTAL_RESUME_REJECTED, RENTAL_RESUME_WITHDRAWN
    }
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final JsonMapper json = JsonMapper.builder().build();
    private final RentalRequestNotificationOutboxRepository outbox;
    private final String topic;

    public RentalRequestNotificationWriter(RentalRequestNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    /** Notifies every persisted party except the actor: owner acts -> renter, renter acts -> owner, SYSTEM/admin -> both. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueOthers(RentalRequest request, String actorId, Event type) {
        if (actorId == null || actorId.isBlank()) throw new IllegalStateException("Rental request actor missing");
        if (!actorId.equals(request.getOwnerId())) enqueue(request, type, Side.OWNER);
        if (!actorId.equals(request.getRenterId())) enqueue(request, type, Side.RENTER);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(RentalRequest request, Event type, Side side) {
        String recipient = side == Side.OWNER ? request.getOwnerId() : request.getRenterId();
        if (request.getId() == null || request.getId().isBlank()) throw new IllegalStateException("Rental request id missing");
        if (recipient == null || recipient.isBlank()) throw new IllegalStateException("Rental request recipient missing");
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, String> data = new HashMap<>();
        data.put("requestId", request.getId());
        data.put("recipientSide", side.name());
        if (request.getListing() != null && request.getListing().getTitle() != null) {
            String title = request.getListing().getTitle().replaceAll("[<>\\p{Cntrl}]", " ")
                    .replaceAll("\\s+", " ").trim();
            if (!title.isEmpty()) data.put("listingTitle", title.substring(0, Math.min(title.length(), 120)).trim());
        }
        String payload = json.writeValueAsString(Map.of(
                "eventId", eventId, "eventType", type.name(), "version", 1,
                "occurredAt", TIMESTAMP.format(now), "recipients", List.of(recipient),
                "data", data));
        outbox.save(new RentalRequestNotificationOutbox(eventId, request.getId(), topic, payload, now));
    }
}
