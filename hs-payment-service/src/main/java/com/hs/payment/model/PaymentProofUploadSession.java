package com.hs.payment.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.payment.model.constant.UploadSessionStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "payment_proof_upload_sessions",
        indexes = {
                @Index(name = "idx_proof_session_request", columnList = "payment_request_id"),
                @Index(name = "idx_proof_session_tenant", columnList = "tenant_user_id"),
                @Index(name = "idx_proof_session_token_hash", columnList = "token_hash", unique = true)
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentProofUploadSession extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    String id;

    @Column(name = "payment_request_id", nullable = false, length = 36)
    String paymentRequestId;

    @Column(name = "tenant_user_id", nullable = false, length = 36)
    String tenantUserId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    String tokenHash;

    @Column(name = "storage_id", length = 36)
    String storageId;

    @Column(name = "original_file_name", length = 255)
    String originalFileName;

    @Column(name = "content_type", length = 100)
    String contentType;

    @Column(name = "file_size")
    Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    UploadSessionStatus status = UploadSessionStatus.CREATED;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "uploaded_at")
    Instant uploadedAt;

    @Column(name = "consumed_at")
    Instant consumedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = UploadSessionStatus.CREATED;
        }
    }
}
