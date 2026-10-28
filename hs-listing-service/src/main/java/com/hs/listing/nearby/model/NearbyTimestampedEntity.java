package com.hs.listing.nearby.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import java.time.Instant;

@Getter
@Setter
@MappedSuperclass
public abstract class NearbyTimestampedEntity extends NearbyIdEntity {
    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    protected void touch() { updatedAt = Instant.now(); }
}
