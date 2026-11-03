package com.hs.payment.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.payment.model.constant.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Shared case foundation for payment disputes and rental complaints. */
@Entity @Table(name="dispute_cases",indexes={
    @Index(name="idx_dispute_subject",columnList="subject_type,subject_id"),
    @Index(name="idx_dispute_queue",columnList="status,opened_at")})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DisputeCase extends BaseEntity {
    @Id @Column(length=36,updatable=false) private String id;
    @Column(name="subject_type",nullable=false,length=30,updatable=false) private String subjectType;
    @Column(name="subject_id",nullable=false,length=36,updatable=false) private String subjectId;
    @Column(name="active_key",unique=true,length=80) private String activeKey;
    @Column(length=36,updatable=false) private String rentalRequestId;
    @Column(length=36,updatable=false) private String contractId;
    @Column(nullable=false,length=36,updatable=false) private String payerId;
    @Column(nullable=false,length=36,updatable=false) private String payeeId;
    @Column(nullable=false,length=36,updatable=false) private String openedBy;
    @Column(nullable=false,length=2000,updatable=false) private String reason;
    @Column(nullable=false,updatable=false) private Instant openedAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private DisputeCaseStatus status;
    @Enumerated(EnumType.STRING) @Column(length=30,updatable=false) private PaymentStatus previousPaymentStatus;
    @Enumerated(EnumType.STRING) @Column(length=30,updatable=false) private DepositStatus previousDepositStatus;
    @Column(nullable=false,precision=15,scale=2,updatable=false) private BigDecimal amountSnapshot;
    @Column(nullable=false,length=10,updatable=false) private String currency;
    @Column(columnDefinition="TEXT",nullable=false,updatable=false) private String initialEvidenceSnapshot;
    @Column(length=36) private String proposalId;
    @Enumerated(EnumType.STRING) @Column(length=30) private DisputeResolution proposedResolution;
    @Column(length=36) private String proposedBy;
    @Column(length=500) private String proposalReason;
    @Column(length=36) private String assignedTo;
    @Column(length=36) private String resolvedBy;
    @Enumerated(EnumType.STRING) @Column(length=30) private DisputeResolution resolution;
    @Column(length=500) private String resolutionReason;
    private Instant resolvedAt;
    // Nullable on existing PAYMENT cases; keep their state machine and enum CHECKs unchanged.
    @Column(length=24) private String complaintCategory;
    @Column(length=32) private String complaintStage;
    private Instant respondBy;
    @Column(length=36) private String responseDueFor;
    private Instant escalatedAt;
    private Long sourceVersion;
    @Column(length=32) private String complaintOutcome;
    @Column(columnDefinition="TEXT") private String complaintDecision;
    @Column(columnDefinition="TEXT") private String complaintProposal;
    private Instant tenantCompletedAt;
    private Instant landlordCompletedAt;
    @Column(length=36) private String complaintRoundOpenedBy;
    private Long complaintRoundStartRevision;
    /** A business revision increments for every action, including notes at the same simulated instant. */
    @Column(nullable=false) private long revision;
    @Version private Long lockVersion;
    @PrePersist void initialize(){if(id==null)id=UUID.randomUUID().toString();}
}
