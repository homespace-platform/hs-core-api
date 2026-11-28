package com.hs.listing.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.*;

/**
 * Counted listing views per listing and Asia/Ho_Chi_Minh day. No viewer ids, no IPs; kept, never purged.
 * Written only by {@code ListingViewDailyRepository.record} (upsert).
 */
@Entity
@Table(name = "listing_view_daily", indexes = @Index(name = "idx_listing_view_daily_date", columnList = "view_date"))
@IdClass(ListingViewDaily.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class ListingViewDaily {
    @Id
    @Column(name = "listing_id", length = 36, updatable = false)
    private String listingId;

    @Id
    @Column(name = "view_date", updatable = false)
    private LocalDate viewDate;

    @Column(name = "views", nullable = false)
    private int views;

    @Column(name = "unique_logged_in_viewers", nullable = false)
    private int uniqueLoggedInViewers;

    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String listingId;
        private LocalDate viewDate;
    }
}
