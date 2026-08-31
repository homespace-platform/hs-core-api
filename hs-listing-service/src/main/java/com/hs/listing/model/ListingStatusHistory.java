package com.hs.listing.model;

import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.ListingStatusActorType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_status_history", indexes = {
        @Index(name = "idx_listing_status_history_listing_created", columnList = "listing_id, created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListingStatusHistory {
    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "listing_id", nullable = false, length = 36)
    private String listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ListingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private ListingStatus toStatus;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "changed_by", length = 255)
    private String changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_type", nullable = false)
    private ListingStatusActorType changedByType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
