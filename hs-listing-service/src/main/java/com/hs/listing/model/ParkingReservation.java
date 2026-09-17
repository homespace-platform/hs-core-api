package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.listing.model.constant.ParkingReservationStatus;
import com.hs.listing.model.constant.VehicleType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "parking_reservations", indexes = {
        @Index(name = "idx_parking_res_branch_status", columnList = "branch_id, status"),
        @Index(name = "idx_parking_res_listing_status", columnList = "listing_id, status"),
        @Index(name = "idx_parking_res_request", columnList = "rental_request_id"),
        @Index(name = "idx_parking_res_contract", columnList = "contract_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParkingReservation extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "listing_id", nullable = false, length = 36)
    private String listingId;

    @Column(name = "rental_request_id", nullable = false, length = 36)
    private String rentalRequestId;

    @Column(name = "contract_id", length = 36)
    private String contractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 30)
    private VehicleType vehicleType;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date_exclusive", nullable = false)
    private LocalDate endDateExclusive;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ParkingReservationStatus status;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @PrePersist
    void initId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = ParkingReservationStatus.HELD;
        }
    }
}
