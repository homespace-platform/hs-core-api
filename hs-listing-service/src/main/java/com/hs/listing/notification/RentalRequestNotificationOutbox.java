package com.hs.listing.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "rental_request_notification_outbox", indexes = {
        @Index(name = "idx_rental_request_outbox_pending", columnList = "sent_at,id"),
        @Index(name = "idx_rental_request_outbox_request", columnList = "rental_request_id,id")
})
@Getter
@NoArgsConstructor
public class RentalRequestNotificationOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;
    @Column(name = "rental_request_id", nullable = false, length = 36)
    private String rentalRequestId;
    @Column(nullable = false)
    private String topic;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    public RentalRequestNotificationOutbox(String eventId, String rentalRequestId, String topic, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.rentalRequestId = rentalRequestId;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
