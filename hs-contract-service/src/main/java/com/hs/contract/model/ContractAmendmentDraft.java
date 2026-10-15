package com.hs.contract.model;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** Private workspace: never exposed to the tenant or consumed by billing/signing. */
@Entity @Table(name = "contract_amendment_drafts")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ContractAmendmentDraft extends BaseEntity {
    @Id @Column(name = "contract_id", length = 36) private String contractId;
    @Column(name = "owner_id", nullable = false, length = 36) private String ownerId;
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT") private String payload;
}
