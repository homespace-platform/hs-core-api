package com.hs.listing.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Durable listing notification; separate from the appointment outbox so listing ids never occupy booking_id. */
@Entity
@Table(name = "listing_notification_outbox", indexes = {
        @Index(name = "idx_listing_outbox_pending", columnList = "sent_at,id"),
        @Index(name = "idx_listing_outbox_listing", columnList = "listing_id,id")
})
@Getter
@NoArgsConstructor
public class ListingNotificationOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;
    @Column(name = "listing_id", nullable = false, length = 36)
    private String listingId;
    @Column(nullable = false)
    private String topic;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    public ListingNotificationOutbox(String eventId, String listingId, String topic, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.listingId = listingId;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
