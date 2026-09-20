package com.hs.payment.model;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "payment_evidences",
        indexes = {
                @Index(name = "idx_payment_evidence_request", columnList = "payment_request_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentEvidence extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    String id;

    @Column(name = "payment_request_id", nullable = false, length = 36)
    String paymentRequestId;

    @Column(name = "uploaded_by", nullable = false, length = 36)
    String uploadedBy;

    @Column(name = "storage_object_id", nullable = false, length = 255)
    String storageObjectId;

    @Column(name = "declared_transfer_time")
    Instant declaredTransferTime;

    @Column(name = "bank_transaction_reference", length = 100)
    String bankTransactionReference;

    @Column(name = "payer_account_last4", length = 4)
    String payerAccountLast4;

    @Column(name = "note", length = 500)
    String note;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
