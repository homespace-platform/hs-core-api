package com.hs.listing.repository;

import com.hs.listing.model.ParkingReservation;
import com.hs.listing.model.constant.ParkingReservationStatus;
import com.hs.listing.model.constant.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface ParkingReservationRepository extends JpaRepository<ParkingReservation, String> {

    @Query("""
        SELECT r FROM ParkingReservation r
        WHERE r.branchId = :branchId
          AND r.vehicleType = :vehicleType
          AND r.status IN :statuses
          AND r.startDate < :endDateExclusive
          AND r.endDateExclusive > :startDate
          AND r.active = true
    """)
    List<ParkingReservation> findOverlappingBranchReservations(
            @Param("branchId") String branchId,
            @Param("vehicleType") VehicleType vehicleType,
            @Param("statuses") Collection<ParkingReservationStatus> statuses,
            @Param("startDate") LocalDate startDate,
            @Param("endDateExclusive") LocalDate endDateExclusive
    );

    @Query("""
        SELECT r FROM ParkingReservation r
        WHERE r.listingId = :listingId
          AND r.vehicleType = :vehicleType
          AND r.status IN :statuses
          AND r.startDate < :endDateExclusive
          AND r.endDateExclusive > :startDate
          AND r.active = true
    """)
    List<ParkingReservation> findOverlappingListingReservations(
            @Param("listingId") String listingId,
            @Param("vehicleType") VehicleType vehicleType,
            @Param("statuses") Collection<ParkingReservationStatus> statuses,
            @Param("startDate") LocalDate startDate,
            @Param("endDateExclusive") LocalDate endDateExclusive
    );

    @Query("""
        SELECT r FROM ParkingReservation r
        WHERE r.branchId = :branchId
          AND r.vehicleType = :vehicleType
          AND r.status IN :statuses
          AND r.active = true
    """)
    List<ParkingReservation> findAllActiveBranchReservations(
            @Param("branchId") String branchId,
            @Param("vehicleType") VehicleType vehicleType,
            @Param("statuses") Collection<ParkingReservationStatus> statuses
    );

    List<ParkingReservation> findByRentalRequestId(String rentalRequestId);

    List<ParkingReservation> findByContractId(String contractId);

    List<ParkingReservation> findAllByStatusAndHoldExpiresAtLessThanEqual(
            ParkingReservationStatus status, Instant now);

    List<ParkingReservation> findAllByStatusAndEndDateExclusiveLessThanEqual(
            ParkingReservationStatus status, LocalDate today);
}
