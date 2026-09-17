package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.listing.model.constant.RentalPaymentStatus;
import com.hs.listing.model.constant.RentalPaymentType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rental_payments",
        indexes = {
                @Index(name = "idx_rental_payment_request", columnList = "rental_request_id"),
                @Index(name = "idx_rental_payment_renter_status", columnList = "renter_id, status"),
                @Index(name = "idx_rental_payment_owner_status", columnList = "owner_id, status")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_rental_request_payment_type", columnNames = {"rental_request_id", "type"})
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RentalPayment extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "rental_request_id", nullable = false, length = 36)
    private String rentalRequestId;

    @Column(name = "listing_id", nullable = false, length = 36)
    private String listingId;

    @Column(name = "renter_id", nullable = false, length = 36)
    private String renterId;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private RentalPaymentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RentalPaymentStatus status;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 10)
    private String currency = "VND";

    @Column(name = "monthly_rent", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyRent;

    @Column(name = "monthly_charges", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyCharges;

    @Column(name = "deposit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal depositAmount;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "cost_breakdown_snapshot", columnDefinition = "TEXT")
    private String costBreakdownSnapshot;

    @Column(name = "excluded_charges_snapshot", columnDefinition = "TEXT")
    private String excludedChargesSnapshot;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "contract_due_at")
    private Instant contractDueAt;

    @Column(name = "provider", length = 50)
    private String provider;

    @Column(name = "provider_transaction_id", length = 100)
    private String providerTransactionId;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (currency == null) {
            currency = "VND";
        }
    }
}
