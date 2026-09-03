package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.listing.model.constant.RentalRequestStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "rental_requests", indexes = {
        @Index(name = "idx_rental_request_listing_status", columnList = "listing_id, status"),
        @Index(name = "idx_rental_request_owner_status", columnList = "owner_id, status"),
        @Index(name = "idx_rental_request_renter_status", columnList = "renter_id, status"),
        @Index(name = "idx_rental_request_expires_status", columnList = "hold_expires_at, status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RentalRequest extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "renter_id", nullable = false, length = 36)
    private String renterId;

    @Column(name = "renter_name", nullable = false, length = 100)
    private String renterName;

    @Column(name = "renter_phone", nullable = false, length = 20)
    private String renterPhone;

    @Column(name = "renter_email", length = 150)
    private String renterEmail;

    @Column(name = "move_in_date", nullable = false)
    private LocalDate moveInDate;

    @Column(name = "lease_months", nullable = false)
    private Integer leaseMonths;

    @Builder.Default
    @Column(name = "occupant_count", nullable = false)
    private Integer occupantCount = 1;

    @Column(name = "monthly_rent_price", nullable = false, precision = 18, scale = 2)
    private java.math.BigDecimal monthlyRentPrice;

    @Column(name = "deposit_amount", precision = 18, scale = 2)
    private java.math.BigDecimal depositAmount;

    @Column(name = "renter_note", columnDefinition = "TEXT")
    private String renterNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RentalRequestStatus status;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;
}
