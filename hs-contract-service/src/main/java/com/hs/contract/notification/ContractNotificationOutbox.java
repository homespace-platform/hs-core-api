package com.hs.contract.notification;

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

/** Durable contract-service notification, committed atomically with the invoice change it describes. */
@Entity
@Table(name = "contract_notification_outbox", indexes = {
        @Index(name = "idx_contract_outbox_pending", columnList = "sent_at,id"),
        @Index(name = "idx_contract_outbox_aggregate", columnList = "aggregate_id,id")
})
@Getter
@NoArgsConstructor
public class ContractNotificationOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;
    @Column(name = "aggregate_id", nullable = false, length = 36)
    private String aggregateId;
    @Column(nullable = false)
    private String topic;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    public ContractNotificationOutbox(String eventId, String aggregateId, String topic, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
