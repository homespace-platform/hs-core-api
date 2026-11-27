package com.hs.listing.model;

import jakarta.persistence.*;
import lombok.*;

/** Floor-map header of a branch. No row means the layout was never set up. */
@Entity
@Table(name = "branch_layouts")
@Getter
@Setter
@NoArgsConstructor
public class BranchLayout {

    @Id
    @Column(name = "branch_id", length = 36)
    private String branchId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private PropertyBranch branch;

    @Column(nullable = false)
    private long revision;
}
