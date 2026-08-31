package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.listing.model.constant.*;
import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "listings", indexes = {
        @Index(name = "idx_listings_owner_status", columnList = "owner_id, status"),
        @Index(name = "idx_listings_status_submitted", columnList = "status, submitted_at"),
        @Index(name = "idx_listings_status_expires", columnList = "status, expires_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Listing extends BaseEntity {
    @Id @Column(length = 36, updatable = false) private String id;
    @Column(name = "owner_id", nullable = false, length = 36) private String ownerId;
    @Column(nullable = false) private String title;
    @Column(nullable = false, columnDefinition = "text") private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ListingCategory category;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ListingSubtype subtype;
    @Enumerated(EnumType.STRING) @Column(name = "rental_mode", nullable = false) private RentalMode rentalMode;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ListingStatus status;
    @Column(name = "status_reason", columnDefinition = "text") private String statusReason;
    @Column(name = "status_changed_at") private Instant statusChangedAt;
    @Column(name = "status_changed_by", length = 255) private String statusChangedBy;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "expires_at") private Instant expiresAt;
    @Version private long version;
    @Column(name = "available_from", nullable = false) private LocalDate availableFrom;
    @Column(name = "area_m2", nullable = false, precision = 12, scale = 2) private BigDecimal areaM2;
    @Column(name = "price_amount", nullable = false, precision = 18, scale = 2) private BigDecimal priceAmount;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(name = "price_unit", nullable = false) private PriceUnit priceUnit;
    @Column(nullable = false) private boolean negotiable;
    @Enumerated(EnumType.STRING) @Column(name = "deposit_type", nullable = false) private DepositType depositType;
    @Column(name = "deposit_amount", precision = 18, scale = 2) private BigDecimal depositAmount;
    @Column(name = "deposit_months") private Integer depositMonths;
    @Enumerated(EnumType.STRING) @Column(name = "payment_cycle", nullable = false) private PaymentCycle paymentCycle;
    @Column(name = "minimum_lease_months", nullable = false) private Integer minimumLeaseMonths;
    @Column(name = "management_fee_included", nullable = false) private boolean managementFeeIncluded;
    @Column(name = "vat_included") private Boolean vatIncluded;

    @OneToOne(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private ListingApartmentDetail apartmentDetail;
    @OneToOne(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private ListingHouseDetail houseDetail;
    @OneToOne(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private ListingOfficeDetail officeDetail;
    @OneToOne(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private ListingCommercialDetail commercialDetail;
    @OneToOne(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private ListingRoomDetail roomDetail;

    @OneToMany(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default private List<ListingMedia> media = new ArrayList<>();
    @OneToMany(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default private List<ListingCharge> charges = new ArrayList<>();
    @OneToMany(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default private List<ListingCustomAmenity> customAmenities = new ArrayList<>();
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "listing_amenities", joinColumns = @JoinColumn(name = "listing_id"), inverseJoinColumns = @JoinColumn(name = "amenity_id"))
    @Builder.Default private Set<Amenity> amenities = new HashSet<>();
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "listing_furnishings", joinColumns = @JoinColumn(name = "listing_id"), inverseJoinColumns = @JoinColumn(name = "furnishing_item_id"))
    @Builder.Default private Set<FurnishingItem> furnishings = new HashSet<>();
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "listing_viewing_days", joinColumns = @JoinColumn(name = "listing_id"))
    @Column(name = "day_of_week", nullable = false) @Enumerated(EnumType.STRING)
    @Builder.Default private Set<DayOfWeek> viewingDays = new HashSet<>();
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "listing_viewing_slots", joinColumns = @JoinColumn(name = "listing_id"))
    @Column(name = "viewing_slot", nullable = false) @Enumerated(EnumType.STRING)
    @Builder.Default private Set<ViewingSlot> viewingSlots = new HashSet<>();

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (status == null) status = ListingStatus.DRAFT;
        if (getActive() == null) setActive(true);
    }
}
