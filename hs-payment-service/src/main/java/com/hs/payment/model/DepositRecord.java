package com.hs.payment.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.payment.model.constant.DepositStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "deposit_records",
        indexes = {
                @Index(name = "idx_deposit_record_rental_request", columnList = "rental_request_id"),
                @Index(name = "idx_deposit_record_contract", columnList = "contract_id"),
                @Index(name = "idx_deposit_record_tenant", columnList = "tenant_id"),
                @Index(name = "idx_deposit_record_landlord", columnList = "landlord_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class DepositRecord extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    String id;

    @Column(name = "rental_request_id", nullable = false, length = 36)
    String rentalRequestId;

    @Column(name = "contract_id", length = 36)
    String contractId;

    @Column(name = "initial_payment_request_id", nullable = false, length = 36)
    String initialPaymentRequestId;

    @Column(name = "landlord_id", nullable = false, length = 36)
    String landlordId;

    @Column(name = "tenant_id", nullable = false, length = 36)
    String tenantId;

    @Column(name = "original_amount", nullable = false, precision = 15, scale = 2)
    BigDecimal originalAmount;

    @Column(name = "held_amount", nullable = false, precision = 15, scale = 2)
    BigDecimal heldAmount;

    @Column(name = "refundable_amount", nullable = false, precision = 15, scale = 2)
    BigDecimal refundableAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    DepositStatus status;

    @Column(name = "received_confirmed_at")
    Instant receivedConfirmedAt;

    @Column(name = "refund_payment_request_id", length = 36)
    String refundPaymentRequestId;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = DepositStatus.PENDING;
        }
    }
}
