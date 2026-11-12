package com.hs.listing.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "appointment_notification_outbox", indexes = {
        @Index(name = "idx_appointment_outbox_pending", columnList = "sent_at,id"),
        @Index(name = "idx_appointment_outbox_booking", columnList = "booking_id,id")
})
@Getter
@NoArgsConstructor
public class AppointmentNotificationOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;
    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;
    @Column(nullable = false)
    private String topic;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    public AppointmentNotificationOutbox(String eventId, String bookingId, String topic, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.bookingId = bookingId;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
