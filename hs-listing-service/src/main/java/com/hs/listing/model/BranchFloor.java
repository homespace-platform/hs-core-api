package com.hs.listing.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "branch_floors", indexes = @Index(name = "idx_branch_floors_branch", columnList = "branch_id"))
@Getter
@Setter
@NoArgsConstructor
public class BranchFloor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false, updatable = false)
    private PropertyBranch branch;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private int position;
}
