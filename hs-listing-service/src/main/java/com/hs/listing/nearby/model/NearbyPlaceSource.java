package com.hs.listing.nearby.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Getter
@Setter
@Table(name = "nearby_place_sources", uniqueConstraints = {
    @UniqueConstraint(name = "uk_nearby_source_identity", columnNames = {"provider", "api_version", "external_id"})
}, indexes = {
    @Index(name = "idx_nearby_source_place_expiry", columnList = "place_id,content_expires_at")
})
public class NearbyPlaceSource extends NearbyIdEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private NearbyPlace place;
    @Column(nullable = false, length = 30)
    private String provider;
    @Column(name = "api_version", nullable = false, length = 16)
    private String apiVersion;
    @Column(name = "external_id", nullable = false, columnDefinition = "text")
    private String externalId;
    @Column(name = "external_info", columnDefinition = "text")
    private String externalInfo;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_ids", nullable = false, columnDefinition = "jsonb")
    @ColumnDefault("'[]'::jsonb")
    private List<String> categoryIds = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> classifications;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_labels", columnDefinition = "jsonb")
    private List<String> categoryLabels;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_address", columnDefinition = "jsonb")
    private Map<String, Object> sourceAddress;
    @Column(name = "license_policy_id", nullable = false, columnDefinition = "text")
    private String licensePolicyId;
    @Column(nullable = false, columnDefinition = "text")
    private String attribution;
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant firstSeenAt = Instant.now();
    @Column(name = "last_seen_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant lastSeenAt = Instant.now();
    @Column(name = "content_expires_at", nullable = false)
    private Instant contentExpiresAt;
}
