package com.hs.listing.nearby.model;

import com.hs.listing.model.Listing;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Getter
@Setter
@Table(name = "listing_nearby_places", indexes = {
    @Index(name = "idx_listing_nearby_distance", columnList = "listing_id,straight_distance_m,place_id"),
    @Index(name = "idx_place_nearby_listings", columnList = "place_id,straight_distance_m,listing_id")
})
@Check(constraints = "straight_distance_m >= 0")
public class ListingNearbyPlace {
    @EmbeddedId
    private ListingNearbyPlaceId id;
    @MapsId("listingId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Listing listing;
    @MapsId("placeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private NearbyPlace place;
    @Column(name = "straight_distance_m", nullable = false, precision = 12, scale = 3)
    private BigDecimal straightDistanceM;
    @Column(name = "listing_location_hash", nullable = false, length = 64)
    private String listingLocationHash;
    @Column(name = "place_position_revision", nullable = false)
    private long placePositionRevision;
    @Column(name = "last_calculated_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant lastCalculatedAt = Instant.now();
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_run_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private NearbyScanRun evidenceRun;
}
