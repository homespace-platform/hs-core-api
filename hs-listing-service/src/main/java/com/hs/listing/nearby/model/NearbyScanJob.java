package com.hs.listing.nearby.model;

import com.hs.listing.model.Listing;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

@Entity
@Getter
@Setter
@Table(name = "nearby_scan_jobs", uniqueConstraints = {
    @UniqueConstraint(name = "uk_nearby_scan_snapshot", columnNames = {"listing_id", "location_hash", "radius_m", "mapping_version"})
}, indexes = {
    @Index(name = "idx_nearby_job_claim", columnList = "status,next_attempt_at,lease_until")
})
@Check(constraints = "radius_m between 100 and 10000 and status in ('PENDING','RUNNING','SUCCESS','PARTIAL','RETRY_WAIT','FAILED','CANCELLED','SUPERSEDED')")
public class NearbyScanJob extends NearbyTimestampedEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Listing listing;
    @Column(name = "location_hash", nullable = false, length = 64)
    private String locationHash;
    @Column(name = "radius_m", nullable = false)
    private int radiusM;
    @Column(name = "mapping_version", nullable = false, length = 50)
    private String mappingVersion;
    @Column(nullable = false, length = 24)
    @ColumnDefault("'PENDING'")
    private String status = "PENDING";
    @Column(nullable = false)
    @ColumnDefault("0")
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant nextAttemptAt = Instant.now();
    @Column(name = "lease_token", length = 36)
    private String leaseToken;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;
}
