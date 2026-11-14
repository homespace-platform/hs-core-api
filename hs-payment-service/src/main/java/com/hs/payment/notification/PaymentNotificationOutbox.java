package com.hs.payment.notification;

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

/** Durable payment/refund notification, committed atomically with the payment or dispute change it describes. */
@Entity
@Table(name = "payment_notification_outbox", indexes = {
        @Index(name = "idx_payment_outbox_pending", columnList = "sent_at,id"),
        @Index(name = "idx_payment_outbox_payment", columnList = "payment_id,id")
})
@Getter
@NoArgsConstructor
public class PaymentNotificationOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;
    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;
    @Column(nullable = false)
    private String topic;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    public PaymentNotificationOutbox(String eventId, String paymentId, String topic, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
