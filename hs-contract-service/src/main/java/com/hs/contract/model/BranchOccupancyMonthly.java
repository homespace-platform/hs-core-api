package com.hs.contract.model;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDate;

@Entity @Table(name = "branch_occupancy_monthly")
@IdClass(BranchOccupancyMonthly.Key.class)
@Getter @Setter @NoArgsConstructor
public class BranchOccupancyMonthly {
    @Id @Column(name = "branch_id", length = 36) private String branchId;
    @Id @Column(name = "month_start") private LocalDate monthStart;
    @Column(nullable = false) private int rooms;
    @Column(name = "occupied_room_days", nullable = false) private int occupiedRoomDays;
    @Column(name = "room_days", nullable = false) private int roomDays;

    @EqualsAndHashCode @NoArgsConstructor @AllArgsConstructor
    public static class Key implements Serializable {
        private String branchId;
        private LocalDate monthStart;
    }
}
