package com.hs.contract.model;

import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.PropertyBranch;
import jakarta.persistence.*;
import lombok.*;

/**
 * Management link of a root lease: a row without a room is the unplaced tray, no row means no branch.
 * Deliberately separate from contracts (and from contracts.branch_id, which parking and listing sync own):
 * it never enters terms, snapshots, PDFs, fees or signing.
 */
@Entity
@Table(name = "branch_contract_assignments", indexes = {
        @Index(name = "idx_branch_contract_assignments_branch", columnList = "management_branch_id"),
        @Index(name = "idx_branch_contract_assignments_room", columnList = "room_id")})
@Getter
@Setter
@NoArgsConstructor
public class BranchContractAssignment {

    @Id
    @Column(name = "contract_id", length = 36, updatable = false)
    private String contractId;

    @Column(name = "management_branch_id", nullable = false, length = 36)
    private String managementBranchId;

    @Column(name = "room_id", length = 36)
    private String roomId;

    // Read-only associations: they only declare the foreign keys.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "management_branch_id", insertable = false, updatable = false)
    private PropertyBranch managementBranch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", insertable = false, updatable = false)
    private BranchSlot room;

    public BranchContractAssignment(String contractId, String managementBranchId, String roomId) {
        this.contractId = contractId;
        this.managementBranchId = managementBranchId;
        this.roomId = roomId;
    }
}
