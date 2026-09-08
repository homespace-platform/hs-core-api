package com.hs.user.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(
        name = "kyc_verifications",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_kyc_provider_session", columnNames = {"provider", "provider_session_id"})
        },
        indexes = {
                @Index(name = "idx_kyc_user_id", columnList = "user_id"),
                @Index(name = "idx_kyc_user_status", columnList = "user_id, status")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class KycVerification extends BaseEntity {

    @Id
    @Column(length = 36, nullable = false)
    String id;

    @Column(name = "user_id", length = 36, nullable = false)
    String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    KycProvider provider;

    @Column(name = "provider_session_id", nullable = false, length = 128)
    String providerSessionId;

    @Column(name = "workflow_id", length = 128)
    String workflowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    KycStatus status;

    /** Raw Didit status label, e.g. Approved / In Progress. */
    @Column(name = "provider_status", length = 64)
    String providerStatus;

    @Column(name = "session_url", length = 2048)
    String sessionUrl;

    @Column(name = "rejection_reason", length = 512)
    String rejectionReason;

    @Column(name = "last_event_id", length = 128)
    String lastEventId;

    @Column(name = "verified_at")
    Instant verifiedAt;

    public static KycVerification createPending(
            String userId,
            String providerSessionId,
            String workflowId,
            String sessionUrl,
            String providerStatus
    ) {
        return KycVerification.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .provider(KycProvider.DIDIT)
                .providerSessionId(providerSessionId)
                .workflowId(workflowId)
                .status(KycStatus.PENDING)
                .providerStatus(providerStatus != null ? providerStatus : "Not Started")
                .sessionUrl(sessionUrl)
                .build();
    }
}
