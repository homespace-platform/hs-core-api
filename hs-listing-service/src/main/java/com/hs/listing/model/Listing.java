package com.hs.listing.model;

import java.math.BigDecimal;
import java.util.UUID;

import com.hs.common.persistence.BaseEntity;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "listings", indexes = {
        @Index(name = "idx_listing_owner", columnList = "owner_id"),
        @Index(name = "idx_listing_status", columnList = "status"),
        @Index(name = "idx_listing_category", columnList = "category")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Listing extends BaseEntity {

    @Id
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    String id;

    @Column(nullable = false, length = 255)
    String title;

    @Column(columnDefinition = "TEXT")
    String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    ListingCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    ListingStatus status;

    @Column(name = "owner_id", nullable = false, length = 255)
    String ownerId;

    @Column(name = "price_monthly", precision = 15, scale = 2)
    BigDecimal priceMonthly;

    @Column(name = "deposit_amount", precision = 15, scale = 2)
    BigDecimal depositAmount;

    @Column(name = "area_m2", precision = 10, scale = 2)
    BigDecimal areaM2;

    Integer bedrooms;
    Integer bathrooms;

    @Column(name = "province_code", length = 20)
    String provinceCode;

    @Column(name = "district_code", length = 20)
    String districtCode;

    @Column(name = "ward_code", length = 20)
    String wardCode;

    @Column(length = 500)
    String address;

    @Column(name = "details_json", columnDefinition = "TEXT")
    String detailsJson;

    @Column(name = "image_urls_json", columnDefinition = "TEXT")
    String imageUrlsJson;

    @Column(name = "video_urls_json", columnDefinition = "TEXT")
    String videoUrlsJson;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (status == null) status = ListingStatus.DRAFT;
    }
}
