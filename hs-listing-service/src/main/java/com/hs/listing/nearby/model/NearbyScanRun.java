package com.hs.listing.nearby.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Getter
@Setter
@Table(name = "nearby_scan_runs", indexes = {
    @Index(name = "idx_nearby_run_coverage", columnList = "job_id,category_group,expires_at")
})
public class NearbyScanRun extends NearbyIdEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private NearbyScanJob job;
    @Column(nullable = false, length = 30)
    private String provider;
    @Column(name = "api_version", nullable = false, length = 16)
    private String apiVersion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_group", nullable = false)
    private NearbyCategory categoryGroup;
    @Column(name = "center_lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal centerLat;
    @Column(name = "center_lon", nullable = false, precision = 10, scale = 7)
    private BigDecimal centerLon;
    @Column(name = "radius_m", nullable = false)
    private int radiusM;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requested_category_ids", nullable = false, columnDefinition = "jsonb")
    @ColumnDefault("'[]'::jsonb")
    private List<String> requestedCategoryIds = new ArrayList<>();
    @Column(name = "page_size", nullable = false)
    private int pageSize;
    @Column(name = "next_offset", nullable = false)
    @ColumnDefault("0")
    private int nextOffset;
    @Column(name = "pages_fetched", nullable = false)
    @ColumnDefault("0")
    private int pagesFetched;
    @Column(name = "returned_count", nullable = false)
    @ColumnDefault("0")
    private int returnedCount;
    @Column(name = "unique_count", nullable = false)
    @ColumnDefault("0")
    private int uniqueCount;
    @Column(name = "total_results_reported")
    private Integer totalResultsReported;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "page_summaries", nullable = false, columnDefinition = "jsonb")
    @ColumnDefault("'[]'::jsonb")
    private List<Map<String, Object>> pageSummaries = new ArrayList<>();
    @Column(name = "coverage_status", nullable = false, length = 40)
    @ColumnDefault("'PENDING'")
    private String coverageStatus = "PENDING";
    @Column(name = "stopped_reason", length = 80)
    private String stoppedReason;
    @Column(name = "started_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant startedAt = Instant.now();
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "license_policy_id", nullable = false, columnDefinition = "text")
    private String licensePolicyId;
}
