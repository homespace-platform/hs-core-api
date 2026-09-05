package com.hs.contract.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.contract.model.constant.ContractStatus;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "contracts", indexes = {
        @Index(name = "idx_contract_number", columnList = "contract_number", unique = true),
        @Index(name = "idx_contract_rental_request", columnList = "rental_request_id"),
        @Index(name = "idx_contract_landlord_status", columnList = "landlord_id, status"),
        @Index(name = "idx_contract_tenant_status", columnList = "tenant_id, status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Contract extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "contract_number", nullable = false, length = 64, unique = true)
    private String contractNumber;

    @Column(name = "rental_request_id", nullable = false, length = 36)
    private String rentalRequestId;

    @Column(name = "listing_id", nullable = false, length = 36)
    private String listingId;

    @Column(name = "landlord_id", nullable = false, length = 36)
    private String landlordId;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "template_version_id", nullable = false, length = 36)
    private String templateVersionId;

    @Column(name = "current_revision_id", length = 36)
    private String currentRevisionId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ContractStatus status = ContractStatus.DRAFT;

    @Builder.Default
    @OneToMany(mappedBy = "contract", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("revisionNumber DESC")
    private List<ContractRevision> revisions = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = ContractStatus.DRAFT;
        }
    }
}
