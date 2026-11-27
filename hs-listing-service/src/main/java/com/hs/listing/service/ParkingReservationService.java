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
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

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

    @Transactional
    public void shortenHeldLease(String rentalRequestId, LocalDate previousEnd, LocalDate newEnd) {
        var reservations = parkingReservationRepository.findByRentalRequestId(rentalRequestId);
        for (var reservation : reservations) {
            if (reservation.getStatus() == ParkingReservationStatus.ACTIVE)
                throw new AppException(ListingErrorCode.RENTAL_ESTIMATE_INVALID, "Chỗ xe đã được kích hoạt.");
            if (reservation.getStatus() != ParkingReservationStatus.HELD) continue;
            if (!previousEnd.equals(reservation.getEndDateExclusive())
                    || !newEnd.isAfter(reservation.getStartDate()) || !newEnd.isBefore(previousEnd))
                throw new AppException(ListingErrorCode.RENTAL_ESTIMATE_INVALID, "Lịch giữ chỗ xe không khớp kỳ thuê.");
            reservation.setEndDateExclusive(newEnd);
            parkingReservationRepository.save(reservation);
        }
    }

    /**
     * Kích hoạt reservation sang ACTIVE khi hợp đồng có hiệu lực.
     */
    @Transactional
    public void activateReservationsForRequest(String rentalRequestId, String contractId) {
        List<ParkingReservation> reservations = parkingReservationRepository.findByRentalRequestId(rentalRequestId);
        // R-B: same branch lock as the hold paths, then re-read; a branch deleted meanwhile means the standalone pool, with its limit.
        var gone = deletedUnderLock(reservations.stream().filter(r -> r.getStatus() == ParkingReservationStatus.HELD).toList());
        for (ParkingReservation r : reservations) {
            if (r.getStatus() == ParkingReservationStatus.HELD) {
                if (gone.contains(r.getBranchId())) { validateStandalone(r); r.setBranchId(null); }
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

    @Transactional
    public void holdForRenewal(String rootId,String agreementId,LocalDate start,LocalDate end,Instant deadline) {
        var current=parkingReservationRepository.findByContractId(rootId).stream()
                .filter(r->r.getStatus()==ParkingReservationStatus.ACTIVE && start.equals(r.getEndDateExclusive())).toList();
        if(current.isEmpty()) return;
        String listingId=current.get(0).getListingId(), branchId=current.get(0).getBranchId();
        PropertyBranch branch=lockBranchPool(branchId);
        Listing listing=listingRepository.findByIdForUpdate(listingId).orElseThrow();
        Map<VehicleType,Integer> counts=new EnumMap<>(VehicleType.class);
        current.forEach(r->counts.merge(r.getVehicleType(),r.getQuantity(),Integer::sum));
        for(var item:counts.entrySet()) {
            if(getAvailability(branch,listing,item.getKey(),start,end).available()<item.getValue())
                throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED,"Không đủ chỗ xe trong thời gian gia hạn; cần đối soát trước khi duyệt.");
            parkingReservationRepository.save(ParkingReservation.builder().branchId(poolId(branch)).listingId(listingId)
                    .rentalRequestId(current.get(0).getRentalRequestId()).contractId(agreementId)
                    .vehicleType(item.getKey()).quantity(item.getValue()).startDate(start).endDateExclusive(end)
                    .status(ParkingReservationStatus.HELD).holdExpiresAt(deadline).build());
        }
    }
    @Transactional public void activateRenewalHold(String agreementId,String rootId) {
        var holds=parkingReservationRepository.findByContractId(agreementId);
        if(holds.stream().anyMatch(r->r.getStatus()!=ParkingReservationStatus.HELD))
            throw new IllegalStateException("Renewal parking hold no longer valid");
        validateRenewalHold(agreementId,rootId);
        for(var r:holds) {
            r.setBranchId(liveBranch(r.getBranchId()));
            r.setContractId(rootId); r.setStatus(ParkingReservationStatus.ACTIVE); r.setHoldExpiresAt(null);
            parkingReservationRepository.save(r);
        }
    }
    /** Locks each recorded branch in id order and returns the ones that no longer exist. */
    private Set<String> deletedUnderLock(List<ParkingReservation> rows) {
        var gone = new HashSet<String>();
        rows.stream().map(ParkingReservation::getBranchId).filter(Objects::nonNull).distinct().sorted()
                .forEach(id -> { if (lockBranchPool(id) == null) gone.add(id); });
        return gone;
    }

    /** Listing limit for places of one rental moving to the standalone pool; the rental's own rows are not counted twice. */
    private void validateStandalone(ParkingReservation r) {
        var listing = listingRepository.findById(r.getListingId()).orElseThrow();
        // The caller holds rental/contract rows; acceptance locks Listing before Rental, so only a version check here.
        entityManager.lock(listing, jakarta.persistence.LockModeType.OPTIMISTIC);
        Integer cap = r.getVehicleType() == VehicleType.MOTORBIKE ? listing.getMaxMotorbikeCount() : listing.getMaxCarCount();
        var others = parkingReservationRepository.findOverlappingListingReservations(listing.getId(), r.getVehicleType(), ACTIVE_STATUSES,
                r.getStartDate(), r.getEndDateExclusive()).stream()
                .filter(o -> !Objects.equals(o.getRentalRequestId(), r.getRentalRequestId()) && inListingPool(o)).toList();
        int available = Math.max(0, (cap == null ? 0 : cap) - calculatePeakUsage(others, r.getStartDate(), r.getEndDateExclusive()));
        if (r.getQuantity() > available) throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED,
                "Chi nhánh đã bị xóa; bài đăng chỉ còn " + available + " chỗ trong thời gian thuê.");
    }

    /** A branch soft-deleted since approval sends the renewal to the standalone pool, under the fallback appendix check. */
    @Transactional public void validateRenewalHold(String agreementId,String rootId) {
        var held=parkingReservationRepository.findByContractId(agreementId).stream().filter(r->r.getStatus()==ParkingReservationStatus.HELD).toList();
        var gone=deletedUnderLock(held); // R-B: lock and re-read the branch before writing ACTIVE places
        var stranded=held.stream().filter(r->gone.contains(r.getBranchId())).toList();
        if(stranded.isEmpty()) return;
        var first=stranded.get(0);
        var listing=listingRepository.findByIdForUpdate(first.getListingId()).orElseThrow();
        Map<VehicleType,Integer> counts=new EnumMap<>(VehicleType.class);
        stranded.forEach(r->counts.merge(r.getVehicleType(),r.getQuantity(),Integer::sum));
        validateAmendmentCapacity(new AmendmentPool(listing,null,true),rootId,agreementId,first.getStartDate(),first.getEndDateExclusive(),
                counts.getOrDefault(VehicleType.MOTORBIKE,0),counts.getOrDefault(VehicleType.CAR,0));
    }
    @Transactional public void releaseRenewalHold(String agreementId) {
        for(var r:parkingReservationRepository.findByContractId(agreementId)) {
            if(r.getStatus()==ParkingReservationStatus.HELD) { r.setStatus(ParkingReservationStatus.RELEASED); parkingReservationRepository.save(r); }
        }
    }

    /** Listing limits initialize a rental; an amendment is limited by a branch pool, or when it falls back from a deleted one. */
    @Transactional
    public void validateAmendmentCapacity(String rootId, String agreementId, String listingId,
            LocalDate start, LocalDate end, int motorbikes, int cars) {
        var pool = lockAmendmentPool(listingId);
        validateAmendmentCapacity(pool, rootId, agreementId, start, end, motorbikes, cars);
    }

    @Transactional
    public void validateAmendmentCapacityInBranch(String rootId,String agreementId,String listingId,String branchId,
            LocalDate start,LocalDate end,int bikes,int cars) {
        validateAmendmentCapacity(lockSelectedPool(listingId,branchId),rootId,agreementId,start,end,bikes,cars);
    }

    /** lostBranch: a branch was asked for but is soft-deleted, so the standalone listing pool takes over. */
    private record AmendmentPool(Listing listing, PropertyBranch branch, boolean lostBranch) {}

    /** Replace only this unsigned rental's holds under the shared pool lock; never count its old hold twice. */
    @Transactional
    public void replaceDraftHold(RentalRequest request, int bikes, int cars) {
        replaceDraftHoldInBranch(request,bikes,cars,request.getListing().getBranchId());
    }

    @Transactional
    public void replaceDraftHoldInBranch(RentalRequest request,int bikes,int cars,String targetBranchId) {
        var listing=listingRepository.findById(request.getListing().getId()).orElseThrow();
        // The caller already holds the rental lock. Acceptance locks Listing before Rental;
        // do not wait for that Listing here. The branch lock serializes inventory and an
        // optimistic Listing check rolls back if approval changes its limits or branch.
        var branch=lockBranchPool(targetBranchId);
        if(branch!=null && !Objects.equals(listing.getOwnerId(),branch.getOwnerId()))
            throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED,"Chi nhánh không thuộc chủ nhà của hồ sơ thuê.");
        entityManager.lock(listing,jakarta.persistence.LockModeType.OPTIMISTIC);
        var start=request.getMoveInDate();var end=start.plusMonths(request.getLeaseMonths());
        var own=parkingReservationRepository.findByRentalRequestId(request.getId());
        if (own.stream().anyMatch(r->r.getStatus()==ParkingReservationStatus.ACTIVE))
            throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED,"Chỗ xe đã được kích hoạt; dùng phụ lục để thay đổi.");
        if (bikes<0 || cars<0 || bikes>100 || cars>100
                || listing.getRoomDetail()!=null && listing.getRoomDetail().getParkingPolicy()==com.hs.listing.model.constant.ListingEnums.ParkingPolicy.NONE && bikes+cars>0)
            throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED,"Số xe không phù hợp chính sách giữ xe.");
        for(var type:VehicleType.values()) {
            int desired=type==VehicleType.MOTORBIKE?bikes:cars;
            Integer limit=type==VehicleType.MOTORBIKE?listing.getMaxMotorbikeCount():listing.getMaxCarCount();
            Integer capacity=branch==null?limit:type==VehicleType.MOTORBIKE?branch.getMotorbikeParkingCapacity():branch.getCarParkingCapacity();
            var others=branch==null?parkingReservationRepository.findOverlappingListingReservations(listing.getId(),type,ACTIVE_STATUSES,start,end)
                    :parkingReservationRepository.findOverlappingBranchReservations(branch.getId(),type,ACTIVE_STATUSES,start,end);
            var occupied=others.stream().filter(r->branch!=null || inListingPool(r))
                    .filter(r->!Objects.equals(request.getId(),r.getRentalRequestId())).toList();
            int available=Math.max(0,Objects.requireNonNullElse(capacity,0)-calculatePeakUsage(occupied,start,end));
            if(desired>available || limit!=null && desired>limit)
                throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED,"Không đủ chỗ "+(type==VehicleType.MOTORBIKE?"xe máy":"ô tô")+" trong thời hạn thuê.");
        }
        Instant deadline=own.stream().filter(r->r.getStatus()==ParkingReservationStatus.HELD).map(ParkingReservation::getHoldExpiresAt)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(request.getHoldExpiresAt());
        for(var r:own) if(r.getStatus()==ParkingReservationStatus.HELD) {
            r.setStatus(ParkingReservationStatus.RELEASED);parkingReservationRepository.save(r);
        }
        for(var type:VehicleType.values()) {
            int desired=type==VehicleType.MOTORBIKE?bikes:cars;if(desired==0)continue;
            parkingReservationRepository.save(ParkingReservation.builder().listingId(listing.getId()).branchId(poolId(branch))
                    .rentalRequestId(request.getId()).vehicleType(type).quantity(desired).startDate(start).endDateExclusive(end)
                    .status(ParkingReservationStatus.HELD).holdExpiresAt(deadline).build());
        }
    }

    /** Null for no branch and for a soft-deleted one: its rentals fall back to the standalone listing pool. */
    private PropertyBranch lockBranchPool(String branchId) {
        return branchId==null?null:propertyBranchRepository.findByIdForUpdate(branchId).orElse(null);
    }

    private static String poolId(PropertyBranch branch) {
        return branch==null?null:branch.getId();
    }

    /** Scalar query: loads no entity to go stale. */
    private boolean isDeleted(String branchId) {
        return branchId!=null && propertyBranchRepository.isSoftDeleted(branchId);
    }

    /** The branch id, or null for a soft-deleted branch. */
    public String liveBranch(String branchId) {
        return isDeleted(branchId)?null:branchId;
    }

    /** Places the standalone listing holds, including those still recorded against a soft-deleted branch. */
    private boolean inListingPool(ParkingReservation r) {
        return r.getBranchId()==null || isDeleted(r.getBranchId());
    }

    /** Pool already allocated to an unsigned rental. The listing's current branch only seeds a first allocation. */
    public String draftPool(RentalRequest request, boolean pinned, String pinnedBranchId) {
        var own = parkingReservationRepository.findByRentalRequestId(request.getId());
        // Null is a real pool (standalone listing), so pick the row first and read its branch afterwards.
        for (var r : own)
            if (r.getStatus() == ParkingReservationStatus.HELD && r.getContractId() == null) return r.getBranchId();
        if (pinned) return pinnedBranchId;
        // Released rows of a never-synced draft still name the accepted pool (vehicles 2 -> 0 -> 2).
        return own.isEmpty() ? request.getListing().getBranchId() : own.get(0).getBranchId();
    }

    /** Pool the signed rental occupies just before an appendix takes effect. */
    public String amendmentPool(String rootId, String listingId, LocalDate effectiveOn, boolean pinned, String pinnedBranchId) {
        // An appendix must not record a soft-deleted branch as its destination.
        return liveBranch(recordedAmendmentPool(rootId, listingId, effectiveOn, pinned, pinnedBranchId));
    }

    private String recordedAmendmentPool(String rootId, String listingId, LocalDate effectiveOn, boolean pinned, String pinnedBranchId) {
        // The day before: a replaced, still scheduled appendix already owns the segment starting on effectiveOn.
        LocalDate day = effectiveOn.minusDays(1);
        ParkingReservation last = null;
        for (var r : parkingReservationRepository.findByContractId(rootId)) {
            // RELEASED root rows are segments a later appendix replaced before they ever applied.
            if (r.getStatus() == ParkingReservationStatus.RELEASED || r.getStartDate().isAfter(day)) continue;
            if (r.getStatus() == ParkingReservationStatus.ACTIVE && r.getEndDateExclusive().isAfter(day)) return r.getBranchId();
            if (last == null || r.getEndDateExclusive().isAfter(last.getEndDateExclusive())) last = r;
        }
        if (pinned) return pinnedBranchId;
        // No vehicles at that date: the rental's last own segment still names its pool, even a null one.
        if (last != null) return last.getBranchId();
        return listingRepository.findById(listingId).map(Listing::getBranchId).orElse(null);
    }

    private AmendmentPool lockAmendmentPool(String listingId) {
        Listing candidate = listingRepository.findById(listingId).orElseThrow();
        String branchId = candidate.getBranchId();
        PropertyBranch branch = branchId == null ? null : propertyBranchRepository.findByIdForUpdate(branchId).orElseThrow();
        Listing listing = listingRepository.findByIdForUpdate(listingId).orElseThrow();
        if (!Objects.equals(branchId, listing.getBranchId()))
            throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED, "Liên kết chi nhánh đã thay đổi; vui lòng kiểm tra lại phụ lục.");
        return new AmendmentPool(listing, branch, false);
    }

    private AmendmentPool lockSelectedPool(String listingId,String branchId) {
        var listing=listingRepository.findById(listingId).orElseThrow();
        var branch=lockBranchPool(branchId);
        if(branch!=null && !Objects.equals(listing.getOwnerId(),branch.getOwnerId()))
            throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED,"Chi nhánh không thuộc chủ nhà của hợp đồng.");
        entityManager.lock(listing,jakarta.persistence.LockModeType.OPTIMISTIC);
        return new AmendmentPool(listing,branch,branchId!=null && branch==null);
    }

    private void validateAmendmentCapacity(AmendmentPool pool, String rootId, String agreementId,
            LocalDate start, LocalDate end, int motorbikes, int cars) {
        if (start == null || end == null || !end.isAfter(start) || motorbikes < 0 || cars < 0 || motorbikes > 100 || cars > 100)
            throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED, "Ngày hoặc số xe của phụ lục không hợp lệ.");
        // A listing that is simply standalone keeps no appendix limit.
        if(pool.branch()==null && !pool.lostBranch() && !leavesDeletedBranch(rootId,start))return;
        for (VehicleType type : VehicleType.values()) {
            int desired = type == VehicleType.MOTORBIKE ? motorbikes : cars;
            if (desired == 0) continue;
            Integer capacity = pool.branch()==null? (type==VehicleType.MOTORBIKE?pool.listing().getMaxMotorbikeCount():pool.listing().getMaxCarCount())
                    :type == VehicleType.MOTORBIKE ? pool.branch().getMotorbikeParkingCapacity() : pool.branch().getCarParkingCapacity();
            var bookings=pool.branch()==null?parkingReservationRepository.findOverlappingListingReservations(pool.listing().getId(),type,ACTIVE_STATUSES,start,end)
                    :parkingReservationRepository.findOverlappingBranchReservations(pool.branch().getId(), type, ACTIVE_STATUSES, start, end);
            var others = bookings.stream()
                    .filter(r->pool.branch()!=null || inListingPool(r))
                    // The root's future segment is replaced, not booked a second time.
                    .filter(r -> !(Objects.equals(rootId, r.getContractId()) && r.getStatus() == ParkingReservationStatus.ACTIVE))
                    .filter(r -> !(agreementId != null && Objects.equals(agreementId, r.getContractId()) && r.getStatus() == ParkingReservationStatus.HELD))
                    .toList();
            int available = Math.max(0, (capacity == null ? 0 : capacity) - calculatePeakUsage(others, start, end));
            if (desired > available) throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED,
                    (pool.branch()==null ? "Bài đăng" : "Chi nhánh") + " chỉ còn " + available + " chỗ " + (type == VehicleType.MOTORBIKE ? "xe máy" : "ô tô")
                            + " trong thời gian áp dụng; phụ lục đề xuất " + desired + " chỗ.");
        }
    }

    /** Checks a branch fallback that changes neither vehicles nor term: every held segment, over its own range. */
    @Transactional
    public void validateFallbackMove(String rootId, String agreementId, String listingId, LocalDate from) {
        var pool = new AmendmentPool(lockSelectedPool(listingId, null).listing(), null, true);
        var held = heldFrom(rootId, from);
        for (var r : held) {
            LocalDate start = r.getStartDate().isAfter(from) ? r.getStartDate() : from;
            int quantity = calculatePeakUsage(held.stream().filter(o -> o.getVehicleType() == r.getVehicleType()).toList(),
                    start, r.getEndDateExclusive());
            validateAmendmentCapacity(pool, rootId, agreementId, start, r.getEndDateExclusive(),
                    r.getVehicleType() == VehicleType.MOTORBIKE ? quantity : 0, r.getVehicleType() == VehicleType.CAR ? quantity : 0);
        }
    }

    /** Moves the places from the date on to the standalone pool as they are: same quantities, same end dates (renewals too). */
    @Transactional
    public void moveToListingPool(String rootId, String agreementId, String listingId, LocalDate from) {
        validateFallbackMove(rootId, agreementId, listingId, from);
        releaseRenewalHold(agreementId);
        for (var r : heldFrom(rootId, from)) {
            if (r.getStartDate().isBefore(from)) {
                parkingReservationRepository.save(ParkingReservation.builder().contractId(rootId).listingId(r.getListingId())
                        .rentalRequestId(r.getRentalRequestId()).vehicleType(r.getVehicleType()).quantity(r.getQuantity())
                        .startDate(from).endDateExclusive(r.getEndDateExclusive()).status(ParkingReservationStatus.ACTIVE).build());
                r.setEndDateExclusive(from);
            } else r.setBranchId(null);
            parkingReservationRepository.save(r);
        }
    }

    /** The rental holds places from the date on, and every one of them is already recorded in this pool. */
    public boolean holdsOnlyIn(String rootId, LocalDate from, String branchId) {
        var held = heldFrom(rootId, from);
        return !held.isEmpty() && held.stream().allMatch(r -> Objects.equals(r.getBranchId(), branchId));
    }

    private List<ParkingReservation> heldFrom(String rootId, LocalDate from) {
        return parkingReservationRepository.findByContractId(rootId).stream()
                .filter(r -> r.getStatus() == ParkingReservationStatus.ACTIVE && r.getEndDateExclusive().isAfter(from)).toList();
    }

    /** The rental's own places in a deleted branch reach this period; one ending on the start date covers a replaced appendix. */
    private boolean leavesDeletedBranch(String rootId, LocalDate start) {
        return parkingReservationRepository.findByContractId(rootId).stream()
                .anyMatch(r -> r.getStatus() == ParkingReservationStatus.ACTIVE && !r.getEndDateExclusive().isBefore(start)
                        && isDeleted(r.getBranchId()));
    }

    /** Hold the delta at EVERY root reservation boundary, including newly extended lease dates. */
    @Transactional
    public void holdForAmendment(String rootId, String agreementId, String rentalId, String listingId,
            LocalDate start, LocalDate end, int motorbikes, int cars, Instant deadline) {
        holdInPool(lockAmendmentPool(listingId),rootId,agreementId,rentalId,listingId,start,end,motorbikes,cars,deadline);
    }

    @Transactional
    public void holdForAmendmentInBranch(String rootId,String agreementId,String rentalId,String listingId,String branchId,
            LocalDate start,LocalDate end,int bikes,int cars,Instant deadline) {
        holdInPool(lockSelectedPool(listingId,branchId),rootId,agreementId,rentalId,listingId,start,end,bikes,cars,deadline);
    }

    private void holdInPool(AmendmentPool pool,String rootId,String agreementId,String rentalId,String listingId,
            LocalDate start,LocalDate end,int motorbikes,int cars,Instant deadline) {
        validateAmendmentCapacity(pool, rootId, agreementId, start, end, motorbikes, cars);
        Listing listing = pool.listing();
        var existing = parkingReservationRepository.findByContractId(rootId).stream()
                .filter(r -> r.getStatus() == ParkingReservationStatus.ACTIVE && r.getStartDate().isBefore(end) && r.getEndDateExclusive().isAfter(start)).toList();
        for (VehicleType type : VehicleType.values()) {
            int desired = type == VehicleType.MOTORBIKE ? motorbikes : cars;
            if (desired == 0) continue;
            var boundaries = new TreeSet<LocalDate>(List.of(start, end));
            for (var r : existing) if (r.getVehicleType() == type) {
                if (r.getStartDate().isAfter(start)) boundaries.add(r.getStartDate());
                if (r.getEndDateExclusive().isBefore(end)) boundaries.add(r.getEndDateExclusive());
            }
            var dates = new ArrayList<>(boundaries);
            for (int i = 0; i < dates.size() - 1; i++) {
                LocalDate segmentStart = dates.get(i), segmentEnd = dates.get(i + 1);
                // Same pool identification as the capacity check, or the increment is counted twice against other rentals.
                int covered = existing.stream().filter(r -> pool.branch()==null?inListingPool(r):pool.branch().getId().equals(r.getBranchId()))
                        .filter(r -> r.getVehicleType() == type && !r.getStartDate().isAfter(segmentStart) && r.getEndDateExclusive().isAfter(segmentStart))
                        .mapToInt(ParkingReservation::getQuantity).sum();
                int increase = Math.max(0, desired - covered);
                if (increase > 0) parkingReservationRepository.save(ParkingReservation.builder().contractId(agreementId)
                        .branchId(pool.branch()==null?null:pool.branch().getId()).listingId(listingId).rentalRequestId(rentalId).vehicleType(type)
                        .quantity(increase).startDate(segmentStart).endDateExclusive(segmentEnd).status(ParkingReservationStatus.HELD).holdExpiresAt(deadline).build());
            }
        }
    }

    /** A fully signed appendix replaces only the future segment, keeping historical parking intact. */
    @Transactional
    public void activateAmendmentHold(String rootId, String agreementId, String rentalId, String listingId,
            LocalDate start, LocalDate end, int motorbikes, int cars) {
        activateInPool(lockAmendmentPool(listingId),rootId,agreementId,rentalId,listingId,start,end,motorbikes,cars);
    }

    @Transactional
    public void activateAmendmentHoldInBranch(String rootId,String agreementId,String rentalId,String listingId,String branchId,
            LocalDate start,LocalDate end,int bikes,int cars) {
        activateInPool(lockSelectedPool(listingId,branchId),rootId,agreementId,rentalId,listingId,start,end,bikes,cars);
    }

    private void activateInPool(AmendmentPool pool,String rootId,String agreementId,String rentalId,String listingId,
            LocalDate start,LocalDate end,int motorbikes,int cars) {
        validateAmendmentCapacity(pool, rootId, agreementId, start, end, motorbikes, cars);
        Listing listing = pool.listing();
        var holds = parkingReservationRepository.findByContractId(agreementId);
        if (holds.stream().anyMatch(h -> h.getStatus() != ParkingReservationStatus.HELD))
            throw new AppException(ListingErrorCode.PARKING_CAPACITY_CHANGED, "Chỗ xe của phụ lục không còn được giữ.");
        for (var hold : holds) {
            hold.setStatus(ParkingReservationStatus.RELEASED); parkingReservationRepository.save(hold);
        }
        for (var r : parkingReservationRepository.findByContractId(rootId)) {
            if (r.getStatus() != ParkingReservationStatus.ACTIVE || !r.getEndDateExclusive().isAfter(start)) continue;
            if (!r.getStartDate().isBefore(start)) r.setStatus(ParkingReservationStatus.RELEASED);
            else r.setEndDateExclusive(start);
            parkingReservationRepository.save(r);
        }
        for (VehicleType type : VehicleType.values()) {
            int quantity = type == VehicleType.MOTORBIKE ? motorbikes : cars;
            if (quantity > 0) parkingReservationRepository.save(ParkingReservation.builder().contractId(rootId)
                    .branchId(pool.branch()==null?null:pool.branch().getId()).listingId(listingId).rentalRequestId(rentalId).vehicleType(type)
                    .quantity(quantity).startDate(start).endDateExclusive(end).status(ParkingReservationStatus.ACTIVE).build());
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
