package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ParkingReservation;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ParkingReservationStatus;
import com.hs.listing.model.constant.VehicleType;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ParkingReservationRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingReservationService {

    private final ParkingReservationRepository parkingReservationRepository;
    private final PropertyBranchRepository propertyBranchRepository;
    private final ListingRepository listingRepository;

    private static final List<ParkingReservationStatus> ACTIVE_STATUSES = List.of(
            ParkingReservationStatus.HELD,
            ParkingReservationStatus.ACTIVE
    );

    /**
     * Tính toán capacity, số chỗ đã giữ (peak reserved) và số chỗ còn khả dụng trong khoảng thời gian [startDate, endDateExclusive).
     */
    @Transactional(readOnly = true)
    public VehicleAvailabilityInfo getAvailability(
            PropertyBranch branch,
            Listing listing,
            VehicleType vehicleType,
            LocalDate startDate,
            LocalDate endDateExclusive) {

        if (startDate == null || endDateExclusive == null || !endDateExclusive.isAfter(startDate)) {
            return new VehicleAvailabilityInfo(0, 0, 0);
        }

        if (branch != null) {
            int branchCapacity = vehicleType == VehicleType.MOTORBIKE
                    ? (branch.getMotorbikeParkingCapacity() != null ? branch.getMotorbikeParkingCapacity() : 0)
                    : (branch.getCarParkingCapacity() != null ? branch.getCarParkingCapacity() : 0);

            List<ParkingReservation> overlapping = parkingReservationRepository.findOverlappingBranchReservations(
                    branch.getId(), vehicleType, ACTIVE_STATUSES, startDate, endDateExclusive);

            int branchReserved = calculatePeakUsage(overlapping, startDate, endDateExclusive);
            int branchAvailable = Math.max(0, branchCapacity - branchReserved);

            Integer listingLimit = vehicleType == VehicleType.MOTORBIKE
                    ? listing.getMaxMotorbikeCount()
                    : listing.getMaxCarCount();

            if (listingLimit != null && listingLimit >= 0) {
                int effectiveCapacity = Math.min(listingLimit, branchCapacity);
                int effectiveAvailable = Math.min(listingLimit, branchAvailable);
                int effectiveReserved = Math.max(0, effectiveCapacity - effectiveAvailable);
                return new VehicleAvailabilityInfo(effectiveCapacity, effectiveReserved, effectiveAvailable);
            }

            return new VehicleAvailabilityInfo(branchCapacity, branchReserved, branchAvailable);
        } else {
            // Listing không thuộc branch: dùng pool riêng của listing
            int listingCapacity = vehicleType == VehicleType.MOTORBIKE
                    ? (listing.getMaxMotorbikeCount() != null ? listing.getMaxMotorbikeCount() : 0)
                    : (listing.getMaxCarCount() != null ? listing.getMaxCarCount() : 0);

            List<ParkingReservation> overlapping = parkingReservationRepository.findOverlappingListingReservations(
                    listing.getId(), vehicleType, ACTIVE_STATUSES, startDate, endDateExclusive);

            int reserved = calculatePeakUsage(overlapping, startDate, endDateExclusive);
            int available = Math.max(0, listingCapacity - reserved);

            return new VehicleAvailabilityInfo(listingCapacity, reserved, available);
        }
    }

    /**
     * Tính toán mức sử dụng đồng thời lớn nhất (peak concurrent usage) trên toàn bộ thời gian của các reservation HELD/ACTIVE.
     */
    @Transactional(readOnly = true)
    public int getMaxConcurrentUsage(String branchId, VehicleType vehicleType) {
        List<ParkingReservation> activeReservations = parkingReservationRepository.findAllActiveBranchReservations(
                branchId, vehicleType, ACTIVE_STATUSES);
        if (activeReservations.isEmpty()) {
            return 0;
        }

        Set<LocalDate> criticalDates = new HashSet<>();
        for (ParkingReservation r : activeReservations) {
            criticalDates.add(r.getStartDate());
        }

        int maxUsage = 0;
        for (LocalDate d : criticalDates) {
            int current = 0;
            for (ParkingReservation r : activeReservations) {
                if (!r.getStartDate().isAfter(d) && r.getEndDateExclusive().isAfter(d)) {
                    current += (r.getQuantity() != null ? r.getQuantity() : 0);
                }
            }
            if (current > maxUsage) {
                maxUsage = current;
            }
        }
        return maxUsage;
    }

    /**
     * Tính peak usage trong khoảng [startDate, endDateExclusive) bằng sweep-line trên các mốc start dates.
     */
    public int calculatePeakUsage(List<ParkingReservation> reservations, LocalDate startDate, LocalDate endDateExclusive) {
        if (reservations == null || reservations.isEmpty()) {
            return 0;
        }

        Set<LocalDate> criticalDates = new HashSet<>();
        criticalDates.add(startDate);
        for (ParkingReservation r : reservations) {
            if (r.getStartDate().isAfter(startDate) && r.getStartDate().isBefore(endDateExclusive)) {
                criticalDates.add(r.getStartDate());
            }
        }

        int peak = 0;
        for (LocalDate date : criticalDates) {
            int currentUsage = 0;
            for (ParkingReservation r : reservations) {
                if (!r.getStartDate().isAfter(date) && r.getEndDateExclusive().isAfter(date)) {
                    currentUsage += (r.getQuantity() != null ? r.getQuantity() : 0);
                }
            }
            if (currentUsage > peak) {
                peak = currentUsage;
            }
        }
        return peak;
    }

    /**
     * Tạo các reservation ở trạng thái HELD khi chủ nhà ACCEPT rental request.
     */
    @Transactional
    public List<ParkingReservation> createHeldReservations(RentalRequest request, Listing listing, Instant holdExpiresAt) {
        LocalDate startDate = request.getMoveInDate();
        LocalDate endDateExclusive = request.getMoveInDate().plusMonths(request.getLeaseMonths());
        String branchId = listing.getBranchId();
        List<ParkingReservation> created = new ArrayList<>();

        if (request.getMotorbikeCount() != null && request.getMotorbikeCount() > 0) {
            ParkingReservation motorbikeRes = ParkingReservation.builder()
                    .id(UUID.randomUUID().toString())
                    .branchId(branchId)
                    .listingId(listing.getId())
                    .rentalRequestId(request.getId())
                    .vehicleType(VehicleType.MOTORBIKE)
                    .quantity(request.getMotorbikeCount())
                    .startDate(startDate)
                    .endDateExclusive(endDateExclusive)
                    .status(ParkingReservationStatus.HELD)
                    .holdExpiresAt(holdExpiresAt)
                    .build();
            created.add(parkingReservationRepository.save(motorbikeRes));
        }

        if (request.getCarCount() != null && request.getCarCount() > 0) {
            ParkingReservation carRes = ParkingReservation.builder()
                    .id(UUID.randomUUID().toString())
                    .branchId(branchId)
                    .listingId(listing.getId())
                    .rentalRequestId(request.getId())
                    .vehicleType(VehicleType.CAR)
                    .quantity(request.getCarCount())
                    .startDate(startDate)
                    .endDateExclusive(endDateExclusive)
                    .status(ParkingReservationStatus.HELD)
                    .holdExpiresAt(holdExpiresAt)
                    .build();
            created.add(parkingReservationRepository.save(carRes));
        }

        return created;
    }

    /**
     * Giải phóng reservation khi rental request bị hủy, từ chối hoặc hết hạn.
     */
    @Transactional
    public void releaseReservationsForRequest(String rentalRequestId) {
        List<ParkingReservation> reservations = parkingReservationRepository.findByRentalRequestId(rentalRequestId);
        for (ParkingReservation r : reservations) {
            if (r.getStatus() == ParkingReservationStatus.HELD || r.getStatus() == ParkingReservationStatus.ACTIVE) {
                r.setStatus(ParkingReservationStatus.RELEASED);
                parkingReservationRepository.save(r);
            }
        }
    }

    /**
     * Chuyển reservation sang EXPIRED khi hold hết hạn.
     */
    @Transactional
    public void expireReservationsForRequest(String rentalRequestId) {
        List<ParkingReservation> reservations = parkingReservationRepository.findByRentalRequestId(rentalRequestId);
        for (ParkingReservation r : reservations) {
            if (r.getStatus() == ParkingReservationStatus.HELD) {
                r.setStatus(ParkingReservationStatus.EXPIRED);
                parkingReservationRepository.save(r);
            }
        }
    }

    /**
     * Gia hạn holdExpiresAt cho reservation HELD khi khách hoàn tất thanh toán ban đầu.
     */
    @Transactional
    public void extendHeldReservations(String rentalRequestId, Instant newHoldExpiresAt) {
        List<ParkingReservation> reservations = parkingReservationRepository.findByRentalRequestId(rentalRequestId);
        for (ParkingReservation r : reservations) {
            if (r.getStatus() == ParkingReservationStatus.HELD) {
                r.setHoldExpiresAt(newHoldExpiresAt);
                parkingReservationRepository.save(r);
            }
        }
    }

    /**
     * Kích hoạt reservation sang ACTIVE khi hợp đồng có hiệu lực.
     */
    @Transactional
    public void activateReservationsForRequest(String rentalRequestId, String contractId) {
        List<ParkingReservation> reservations = parkingReservationRepository.findByRentalRequestId(rentalRequestId);
        for (ParkingReservation r : reservations) {
            if (r.getStatus() == ParkingReservationStatus.HELD) {
                r.setStatus(ParkingReservationStatus.ACTIVE);
                r.setContractId(contractId);
                r.setHoldExpiresAt(null);
                parkingReservationRepository.save(r);
            }
        }
    }

    /**
     * Giải phóng reservation theo contractId khi hợp đồng bị hủy hoặc kết thúc.
     */
    @Transactional
    public void releaseReservationsForContract(String contractId) {
        List<ParkingReservation> reservations = parkingReservationRepository.findByContractId(contractId);
        for (ParkingReservation r : reservations) {
            if (r.getStatus() == ParkingReservationStatus.ACTIVE || r.getStatus() == ParkingReservationStatus.HELD) {
                r.setStatus(ParkingReservationStatus.RELEASED);
                parkingReservationRepository.save(r);
            }
        }
    }

    /**
     * Expire các reservation ACTIVE đã qua ngày kết thúc.
     */
    @Transactional
    public int expireOutdatedActiveReservations(LocalDate today) {
        List<ParkingReservation> outdated = parkingReservationRepository.findAllByStatusAndEndDateExclusiveLessThanEqual(
                ParkingReservationStatus.ACTIVE, today);
        for (ParkingReservation r : outdated) {
            r.setStatus(ParkingReservationStatus.EXPIRED);
            parkingReservationRepository.save(r);
        }
        return outdated.size();
    }
}
