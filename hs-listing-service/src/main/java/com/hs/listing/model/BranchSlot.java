package com.hs.listing.model;

import jakarta.persistence.*;
import lombok.*;

/** One cell of a row. With a room code it is a room (id = roomId); without, an aisle/empty cell. */
@Entity
@Table(name = "branch_slots",
        uniqueConstraints = @UniqueConstraint(name = "uk_branch_slots_room_code",
                columnNames = {"branch_id", "normalized_room_code"}),
        indexes = @Index(name = "idx_branch_slots_row", columnList = "row_id"))
@Getter
@Setter
@NoArgsConstructor
public class BranchSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "row_id", nullable = false)
    private BranchRow row;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false, updatable = false)
    private PropertyBranch branch;

    @Column(nullable = false)
    private int position;

    @Column(name = "room_code")
    private String roomCode;

    // Upper-casing can expand one character into up to three, hence the wider column.
    @Column(name = "normalized_room_code", length = 765)
    private String normalizedRoomCode;

    // Nullable so existing rows need no migration; null reads as NONE.
    @Enumerated(EnumType.STRING)
    @Column(name = "manual_status", length = 20)
    private com.hs.listing.model.constant.RoomManualStatus manualStatus;
}
