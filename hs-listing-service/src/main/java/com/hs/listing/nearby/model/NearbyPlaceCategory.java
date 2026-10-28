package com.hs.listing.nearby.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

@Entity
@Getter
@Setter
@Table(name = "nearby_place_categories", indexes = {
    @Index(name = "idx_nearby_category_places", columnList = "category_code,place_id")
})
public class NearbyPlaceCategory {
    @EmbeddedId
    private NearbyPlaceCategoryId id;
    @MapsId("placeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private NearbyPlace place;
    @MapsId("categoryCode")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_code", nullable = false)
    private NearbyCategory category;
    @Column(name = "mapping_version", nullable = false, length = 50)
    private String mappingVersion;
    @Column(name = "classified_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant classifiedAt = Instant.now();
}
