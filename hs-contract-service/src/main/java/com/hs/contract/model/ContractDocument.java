package com.hs.contract.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.contract.model.constant.ContractDocumentType;
import com.hs.contract.model.constant.DocumentGenerationStatus;
import com.hs.contract.model.constant.DocumentPurpose;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contract_documents", indexes = {
        @Index(name = "idx_doc_contract_rev", columnList = "contract_id, revision_id"),
        @Index(name = "idx_doc_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDocument extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "contract_id", nullable = false, length = 36)
    private String contractId;

    @Column(name = "revision_id", nullable = false, length = 36)
    private String revisionId;

    @Column(name = "template_version_id", nullable = false, length = 36)
    private String templateVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private ContractDocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    private DocumentPurpose purpose;

    @Column(name = "storage_object_id", length = 36)
    private String storageObjectId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentGenerationStatus status = DocumentGenerationStatus.GENERATING;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = DocumentGenerationStatus.GENERATING;
        }
    }
}
