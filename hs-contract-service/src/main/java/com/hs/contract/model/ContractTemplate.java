package com.hs.contract.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.contract.model.constant.ContractTemplateStatus;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.RentalMode;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "contract_templates", indexes = {
        @Index(name = "idx_contract_template_status", columnList = "status"),
        @Index(name = "idx_contract_template_category", columnList = "category")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractTemplate extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    private ListingCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "rental_mode", length = 30)
    private RentalMode rentalMode;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContractTemplateStatus status = ContractTemplateStatus.ACTIVE;

    @Column(name = "latest_published_version_id", length = 36)
    private String latestPublishedVersionId;

    @Builder.Default
    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("versionNumber DESC")
    private List<ContractTemplateVersion> versions = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = ContractTemplateStatus.ACTIVE;
        }
    }
}
