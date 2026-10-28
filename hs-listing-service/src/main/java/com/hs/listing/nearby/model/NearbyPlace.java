package com.hs.listing.nearby.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Getter
@Setter
@Table(name = "nearby_places", indexes = {
    @Index(name = "idx_nearby_place_name", columnList = "normalized_name"),
    @Index(name = "idx_nearby_place_bbox", columnList = "latitude,longitude")
})
@Check(constraints = "latitude between -90 and 90 and longitude between -180 and 180")
public class NearbyPlace extends NearbyTimestampedEntity {
    @Column(nullable = false, length = 500)
    private String name;
    @Column(name = "normalized_name", nullable = false, length = 500)
    private String normalizedName;
    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;
    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;
    @Column(name = "address_text", columnDefinition = "text")
    private String addressText;
    @Column(name = "position_revision", nullable = false)
    @ColumnDefault("0")
    private long positionRevision;
    @Column(name = "business_status", nullable = false, length = 30)
    @ColumnDefault("'UNKNOWN'")
    private String businessStatus = "UNKNOWN";
    @Column(name = "verification_status", nullable = false, length = 30)
    @ColumnDefault("'UNVERIFIED'")
    private String verificationStatus = "UNVERIFIED";
    @Column(name = "verified_at")
    private Instant verifiedAt;
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean active = true;
}
