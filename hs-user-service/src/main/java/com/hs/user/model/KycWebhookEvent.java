package com.hs.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

/** Idempotency store for Didit webhook event_id. */
@Entity
@Table(name = "kyc_webhook_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class KycWebhookEvent {

    @Id
    @Column(name = "event_id", length = 128, nullable = false)
    String eventId;

    @Column(name = "session_id", length = 128)
    String sessionId;

    @Column(name = "webhook_type", length = 64)
    String webhookType;

    @Column(name = "processed_at", nullable = false)
    Instant processedAt;
}
