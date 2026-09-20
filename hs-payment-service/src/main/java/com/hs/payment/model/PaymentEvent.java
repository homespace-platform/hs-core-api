package com.hs.payment.model;

import com.hs.payment.model.constant.PaymentEventType;
import com.hs.payment.model.constant.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "payment_events",
        indexes = {
                @Index(name = "idx_payment_event_request_created", columnList = "payment_request_id, created_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentEvent {

    @Id
    @Column(length = 36, updatable = false)
    String id;

    @Column(name = "payment_request_id", nullable = false, length = 36, updatable = false)
    String paymentRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    PaymentEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30, updatable = false)
    PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30, updatable = false)
    PaymentStatus toStatus;

    @Column(name = "actor_id", nullable = false, length = 36, updatable = false)
    String actorId;

    @Column(name = "actor_role", length = 50, updatable = false)
    String actorRole;

    @Column(name = "reason", length = 500, updatable = false)
    String reason;

    @Column(name = "metadata_json", columnDefinition = "TEXT", updatable = false)
    String metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
