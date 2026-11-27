package com.hs.listing.model;

import jakarta.persistence.*;
import lombok.*;

/** One column of rooms inside a floor. */
@Entity
@Table(name = "branch_rows", indexes = @Index(name = "idx_branch_rows_floor", columnList = "floor_id"))
@Getter
@Setter
@NoArgsConstructor
public class BranchRow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_id", nullable = false)
    private BranchFloor floor;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private int position;
}
