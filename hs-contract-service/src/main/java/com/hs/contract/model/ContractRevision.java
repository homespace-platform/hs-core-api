package com.hs.contract.model;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "contract_revisions", indexes = {
        @Index(name = "idx_revision_contract_number", columnList = "contract_id, revision_number", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRevision extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false)
    private Contract contract;

    @Column(name = "revision_number", nullable = false)
    private Integer revisionNumber;

    @Column(name = "template_version_id", nullable = false, length = 36)
    private String templateVersionId;

    @Column(name = "landlord_snapshot", columnDefinition = "TEXT", nullable = false)
    private String landlordSnapshot;

    @Column(name = "tenant_snapshot", columnDefinition = "TEXT", nullable = false)
    private String tenantSnapshot;

    @Column(name = "property_snapshot", columnDefinition = "TEXT", nullable = false)
    private String propertySnapshot;

    @Column(name = "lease_snapshot", columnDefinition = "TEXT", nullable = false)
    private String leaseSnapshot;

    @Column(name = "financial_snapshot", columnDefinition = "TEXT", nullable = false)
    private String financialSnapshot;

    @Column(name = "charges_snapshot", columnDefinition = "TEXT")
    private String chargesSnapshot;

    @Column(name = "equipment_snapshot", columnDefinition = "TEXT")
    private String equipmentSnapshot;

    @Column(name = "initial_meters_snapshot", columnDefinition = "TEXT")
    private String initialMetersSnapshot;

    @Column(name = "special_terms", columnDefinition = "TEXT")
    private String specialTerms;

    @Column(name = "revision_note", length = 500)
    private String revisionNote;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
