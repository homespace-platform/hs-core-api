package com.hs.listing.model;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDate;

/** Logged-in daily uniqueness only; never exposed by analytics, no IP addresses. */
@Entity
@Table(name = "listing_view_daily_viewers")
@IdClass(ListingViewDailyViewer.Key.class)
@Getter @Setter @NoArgsConstructor
public class ListingViewDailyViewer {
    @Id @Column(name = "listing_id", length = 36) private String listingId;
    @Id @Column(name = "view_date") private LocalDate viewDate;
    @Id @Column(name = "viewer_id", length = 36) private String viewerId;

    @EqualsAndHashCode @NoArgsConstructor @AllArgsConstructor
    public static class Key implements Serializable {
        private String listingId;
        private LocalDate viewDate;
        private String viewerId;
    }
}
