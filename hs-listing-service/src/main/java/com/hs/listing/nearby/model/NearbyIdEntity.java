package com.hs.listing.nearby.model;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Getter
@Setter
@MappedSuperclass
public abstract class NearbyIdEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void initializeId() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
    }
}
