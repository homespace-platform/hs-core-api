package com.hs.listing.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "listing_images", uniqueConstraints = {
        @UniqueConstraint(name = "uk_listing_image_storage", columnNames = { "listing_id", "storage_id" })
}, indexes = @Index(name = "idx_listing_images_listing", columnList = "listing_id, sort_order"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ListingImage {

    @Id
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    String id;

    @Column(name = "listing_id", nullable = false, length = 36)
    String listingId;

    @Column(name = "storage_id", nullable = false, length = 36)
    String storageId;

    @Column(name = "sort_order", nullable = false)
    Integer sortOrder;

    @Column(nullable = false)
    Boolean cover;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (sortOrder == null) sortOrder = 0;
        if (cover == null) cover = false;
    }
}
