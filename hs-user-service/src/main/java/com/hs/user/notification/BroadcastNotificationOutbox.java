package com.hs.user.notification;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/** One durable broadcast, with a recipient cursor committed only after Kafka acknowledges a page. */
@Entity
@Table(name = "notification_broadcast_outbox", indexes = {
        @Index(name = "idx_broadcast_outbox_pending", columnList = "sent_at,created_at")
})
@NoArgsConstructor
public class BroadcastNotificationOutbox {
    @Id @Column(name = "event_id", length = 200)
    private String eventId;
    @Column(nullable = false)
    private String topic;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(name = "recipient_cutoff", nullable = false)
    private Instant recipientCutoff;
    @Column(name = "recipient_cursor")
    private String recipientCursor;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;
}
