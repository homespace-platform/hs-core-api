package com.hs.contract.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.contract.model.constant.TemplateVersionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contract_template_versions", indexes = {
        @Index(name = "idx_ctv_template_status", columnList = "template_id, status"),
        @Index(name = "idx_ctv_template_version", columnList = "template_id, version_number", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractTemplateVersion extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private ContractTemplate template;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "storage_object_id", nullable = false, length = 36)
    private String storageObjectId;

    @Column(name = "original_file_name", length = 255)
    private String originalFileName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TemplateVersionStatus status = TemplateVersionStatus.DRAFT;

    @Column(name = "placeholders_json", columnDefinition = "TEXT")
    private String placeholdersJson;

    @Column(name = "validation_errors_json", columnDefinition = "TEXT")
    private String validationErrorsJson;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by", length = 36)
    private String publishedBy;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = TemplateVersionStatus.DRAFT;
        }
    }
}
