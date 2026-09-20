package com.hs.payment.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "payment_requests",
        indexes = {
                @Index(name = "idx_payment_request_rental_request", columnList = "rental_request_id"),
                @Index(name = "idx_payment_request_contract", columnList = "contract_id"),
                @Index(name = "idx_payment_request_payer_status", columnList = "payer_id, status"),
                @Index(name = "idx_payment_request_payee_status", columnList = "payee_id, status"),
                @Index(name = "idx_payment_request_ref", columnList = "transfer_reference")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_request_transfer_ref", columnNames = {"transfer_reference"})
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentRequest extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    String id;

    @Column(name = "rental_request_id", length = 36)
    String rentalRequestId;

    @Column(name = "contract_id", length = 36)
    String contractId;

    @Column(name = "listing_id", length = 36)
    String listingId;

    @Column(name = "payer_id", nullable = false, length = 36)
    String payerId;

    @Column(name = "payee_id", nullable = false, length = 36)
    String payeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    PaymentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 30)
    PaymentDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    PaymentStatus status;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 10)
    String currency = "VND";

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    BigDecimal totalAmount;

    @Column(name = "transfer_reference", nullable = false, length = 20, unique = true)
    String transferReference;

    @Column(name = "bank_transaction_reference", length = 100)
    String bankTransactionReference;

    @Column(name = "payer_bank_account_snapshot", columnDefinition = "TEXT")
    String payerBankAccountSnapshot;

    @Column(name = "payee_bank_account_snapshot", columnDefinition = "TEXT")
    String payeeBankAccountSnapshot;

    @Column(name = "breakdown_snapshot", columnDefinition = "TEXT")
    String breakdownSnapshot;

    @Column(name = "excluded_charges_snapshot", columnDefinition = "TEXT")
    String excludedChargesSnapshot;

    @Column(name = "qr_provider", length = 50)
    String qrProvider;

    @Column(name = "qr_image_url", columnDefinition = "TEXT")
    String qrImageUrl;

    @Column(name = "due_at")
    Instant dueAt;

    @Column(name = "payer_reported_at")
    Instant payerReportedAt;

    @Column(name = "payee_confirmed_at")
    Instant payeeConfirmedAt;

    @Column(name = "confirmed_at")
    Instant confirmedAt;

    @Column(name = "confirmation_due_at")
    Instant confirmationDueAt;

    @Column(name = "contract_due_at")
    Instant contractDueAt;

    @Column(name = "rejected_at")
    Instant rejectedAt;

    @Column(name = "rejected_reason", length = 500)
    String rejectedReason;

    @Column(name = "cancelled_at")
    Instant cancelledAt;

    @Column(name = "cancelled_reason", length = 500)
    String cancelledReason;

    @Column(name = "expired_at")
    Instant expiredAt;

    @Version
    @Column(name = "version")
    Long version;

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
