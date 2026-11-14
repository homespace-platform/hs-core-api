package com.hs.payment.membership.notification;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Durable membership notification, committed atomically with the order status transition it describes. */
@Entity
@Table(name = "membership_notification_outbox", indexes = {
        @Index(name = "idx_membership_outbox_pending", columnList = "sent_at,id"),
        @Index(name = "idx_membership_outbox_order", columnList = "order_id,id")
})
@Getter
@NoArgsConstructor
public class MembershipNotificationOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;
    @Column(name = "order_id", nullable = false, length = 32)
    private String orderId;
    @Column(nullable = false)
    private String topic;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    public MembershipNotificationOutbox(String eventId, String orderId, String topic, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
