package com.hs.contract.blockchain.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Một proof bền vững cho đúng một PDF cuối; các lần gửi lại dùng cùng ID và hash. */
@Entity
@Table(name = "contract_document_proofs", indexes = {
        @Index(name = "idx_document_proof_due", columnList = "status,next_attempt_at,created_at"),
        @Index(name = "idx_document_proof_contract", columnList = "contract_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_document_proof_chain_id", columnNames = {"chain_id", "registry_address", "on_chain_document_id"}),
        @UniqueConstraint(name = "uq_document_proof_nonce", columnNames = {"chain_id", "signer_address", "nonce"})
})
@Getter
@Setter
@NoArgsConstructor
public class ContractDocumentProof {
    @Id
    @Column(name = "document_id", length = 36, updatable = false)
    private String documentId;

    @Column(name = "contract_id", nullable = false, length = 36, updatable = false)
    private String contractId;

    @Column(name = "revision_id", nullable = false, length = 36, updatable = false)
    private String revisionId;

    @Column(name = "storage_object_id", nullable = false, length = 36, updatable = false)
    private String storageObjectId;

    @Column(name = "on_chain_document_id", nullable = false, length = 66, updatable = false)
    private String onChainDocumentId;

    @Column(name = "document_hash", nullable = false, length = 66, updatable = false)
    private String documentHash;

    @Column(name = "case_id", length = 66, updatable = false)
    private String caseId;

    @Column(name = "document_kind", updatable = false)
    private Integer documentKind;

    @Column(name = "chain_id", nullable = false, updatable = false)
    private long chainId;

    @Column(name = "registry_address", nullable = false, length = 42, updatable = false)
    private String registryAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProofStatus status = ProofStatus.PENDING;

    @Column(name = "signer_address", length = 42)
    private String signerAddress;

    @Column(length = 78)
    private String nonce;

    @Column(name = "signed_raw_transaction", columnDefinition = "text")
    private String signedRawTransaction;

    @Column(name = "transaction_hash", length = 66)
    private String transactionHash;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "block_hash", length = 66)
    private String blockHash;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Version
    private Long version;

    @PrePersist
    void created() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (nextAttemptAt == null) nextAttemptAt = now;
        if (status == null) status = ProofStatus.PENDING;
    }

    @PreUpdate
    void updated() {
        updatedAt = Instant.now();
    }
}
