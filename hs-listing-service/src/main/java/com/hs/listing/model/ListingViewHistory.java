package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "listing_view_histories", uniqueConstraints = {
        @UniqueConstraint(name = "uk_view_history_user_listing", columnNames = {"user_id", "listing_id"})
}, indexes = {
        @Index(name = "idx_view_history_user_viewed", columnList = "user_id, viewed_at DESC"),
        @Index(name = "idx_view_history_listing", columnList = "listing_id"),
        @Index(name = "idx_view_history_viewed_at", columnList = "viewed_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListingViewHistory extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;
}
