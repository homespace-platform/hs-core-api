package com.hs.contract.model;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

/** A proposal version is immutable once submitted. Counteroffers create a new row. */
@Entity
@Table(name = "contract_amendments", indexes = {
        @Index(name = "idx_amendment_root", columnList = "contract_id,created_at"),
        @Index(name = "idx_amendment_agreement", columnList = "agreement_contract_id", unique = true),
        @Index(name = "idx_amendment_due", columnList = "status,respond_by")})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ContractAmendment extends BaseEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "contract_id", nullable = false, length = 36) private String contractId;
    @Column(name = "base_revision_id", nullable = false, length = 36) private String baseRevisionId;
    @Column(name = "previous_proposal_id", length = 36) private String previousProposalId;
    @Column(name = "tenant_request_id", length = 36) private String tenantRequestId;
    @Column(name = "proposer_id", nullable = false, length = 36) private String proposerId;
    @Column(name = "recipient_id", nullable = false, length = 36) private String recipientId;
    @Column(nullable = false, length = 25) private String status;
    @Column(name = "effective_on", nullable = false) private LocalDate effectiveOn;
    @Builder.Default @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "immediate_effect", nullable = false) private boolean immediateEffect = false;
    @Column(name = "changes_snapshot", nullable = false, columnDefinition = "TEXT") private String changesSnapshot;
    @Column(name = "comparison_snapshot", nullable = false, columnDefinition = "TEXT") private String comparisonSnapshot;
    @Column(name = "evidence_snapshot", columnDefinition = "TEXT") private String evidenceSnapshot;
    @Column(nullable = false, length = 2000) private String reason;
    @Column(name = "response_note", length = 2000) private String responseNote;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "respond_by") private Instant respondBy;
    @Column(name = "sign_by") private Instant signBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "signed_at") private Instant signedAt;
    @Column(name = "applied_at") private Instant appliedAt;
    @Column(name = "agreement_contract_id", length = 36) private String agreementContractId;
    @Column(name = "terms_revision_id", length = 36) private String termsRevisionId;
    @PrePersist void initialize() { if (id == null) id = UUID.randomUUID().toString(); }
}
