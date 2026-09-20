package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.PageResponse;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.CreateRentalRequest;
import com.hs.listing.dto.request.RejectRentalRequest;
import com.hs.listing.dto.response.InitialPaymentSummary;
import com.hs.listing.dto.response.RentalRequestResponse;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.DepositType;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.port.RentalPaymentLifecyclePort;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.user.model.Address;
import com.hs.user.repository.AddressRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.listing.dto.estimate.RentalEstimateRequest;
import com.hs.listing.dto.estimate.RentalEstimateResponse;
import com.hs.listing.dto.snapshot.ListingSnapshotDto;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.listing.model.Amenity;
import com.hs.listing.model.PropertyBranch;

@Slf4j
@Service
public class RentalRequestService {

    private final RentalRequestRepository rentalRequestRepository;
    private final ListingRepository listingRepository;
    private final ListingStatusService listingStatusService;
    private final AddressRepository addressRepository;
    private final StorageProperties storageProperties;
    private final Duration holdDuration;
    private final RentalCostCalculator rentalCostCalculator;
    private final ParkingReservationService parkingReservationService;
    private final PropertyBranchRepository propertyBranchRepository;
    private final ObjectMapper objectMapper;
    private final RentalPaymentLifecyclePort rentalPaymentLifecyclePort;
    private List<RentalHoldProtectionChecker> holdProtectionCheckers = List.of();

    @Autowired
    public RentalRequestService(
            RentalRequestRepository rentalRequestRepository,
            ListingRepository listingRepository,
            ListingStatusService listingStatusService,
            AddressRepository addressRepository,
            StorageProperties storageProperties,
            RentalCostCalculator rentalCostCalculator,
            ParkingReservationService parkingReservationService,
            PropertyBranchRepository propertyBranchRepository,
            ObjectMapper objectMapper,
            @Autowired(required = false) RentalPaymentLifecyclePort rentalPaymentLifecyclePort,
            @Value("${rental.hold-duration-minutes:15}") long holdDurationMinutes) {
        this.rentalRequestRepository = rentalRequestRepository;
        this.listingRepository = listingRepository;
        this.listingStatusService = listingStatusService;
        this.addressRepository = addressRepository;
        this.storageProperties = storageProperties;
        this.rentalCostCalculator = rentalCostCalculator;
        this.parkingReservationService = parkingReservationService;
        this.propertyBranchRepository = propertyBranchRepository;
        this.objectMapper = objectMapper;
        this.rentalPaymentLifecyclePort = rentalPaymentLifecyclePort;
        this.holdDuration = Duration.ofMinutes(Math.max(holdDurationMinutes, 1L));
    }

    public RentalRequestService(
            RentalRequestRepository rentalRequestRepository,
            ListingRepository listingRepository,
            ListingStatusService listingStatusService,
            AddressRepository addressRepository,
            StorageProperties storageProperties,
            RentalCostCalculator rentalCostCalculator,
            ParkingReservationService parkingReservationService,
            PropertyBranchRepository propertyBranchRepository,
            ObjectMapper objectMapper,
            long holdDurationMinutes) {
        this(rentalRequestRepository, listingRepository, listingStatusService, addressRepository,
                storageProperties, rentalCostCalculator, parkingReservationService, propertyBranchRepository,
                objectMapper, null, holdDurationMinutes);
    }

    @Autowired(required = false)
    void setHoldProtectionCheckers(List<RentalHoldProtectionChecker> holdProtectionCheckers) {
        this.holdProtectionCheckers = holdProtectionCheckers != null ? List.copyOf(holdProtectionCheckers) : List.of();
    }

    // 0. TÍNH TOÁN VÀ ƯỚC TÍNH CHI PHÍ THUÊ (ESTIMATE)
    @Transactional(readOnly = true)
    public RentalEstimateResponse estimateRentalCost(RentalEstimateRequest req) {
        if (req == null || req.listingId() == null || req.listingId().isBlank()) {
            throw new AppException(ListingErrorCode.RENTAL_ESTIMATE_INVALID, "listingId is required");
        }

        Listing listing = listingRepository.findByIdAndActiveTrue(req.listingId())
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));

        int leaseMonths = req.leaseMonths() != null ? req.leaseMonths() : 1;
        int occupants = req.occupantCount() != null ? req.occupantCount() : 1;
        int motorbikes = req.motorbikeCount() != null ? req.motorbikeCount() : 0;
        int cars = req.carCount() != null ? req.carCount() : 0;

        return rentalCostCalculator.calculate(
                listing,
                req.moveInDate(),
                leaseMonths,
                occupants,
                motorbikes,
                cars,
                req.negotiatedDepositAmount()
        );
    }

    // 1. TẠO YÊU CẦU THUÊ NHÀ (RENTER)
    @Transactional
    public RentalRequestResponse createRentalRequest(String renterId, String renterEmail, CreateRentalRequest req) {
        if (renterId == null || renterId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        Listing listing = listingRepository.findByIdAndActiveTrue(req.listingId())
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));

        if (listing.getStatus() == ListingStatus.RESERVED) {
            throw new AppException(ListingErrorCode.LISTING_ALREADY_RESERVED);
        }

        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw new AppException(ListingErrorCode.LISTING_NOT_AVAILABLE_FOR_RENT);
        }

        if (renterId.equals(listing.getOwnerId())) {
            throw new AppException(ListingErrorCode.CANNOT_RENT_OWN_LISTING);
        }

        // Kiểm tra xem khách hàng này đã có yêu cầu nào đang PENDING hoặc ACCEPTED cho bài đăng này chưa
        Optional<RentalRequest> existingActive = rentalRequestRepository.findFirstByListingIdAndRenterIdAndStatusIn(
                listing.getId(), renterId, Set.of(RentalRequestStatus.PENDING, RentalRequestStatus.ACCEPTED));

        if (existingActive.isPresent()) {
            throw new AppException(ListingErrorCode.RENTAL_REQUEST_ALREADY_EXISTS);
        }

        int leaseMonths = req.leaseMonths() != null ? req.leaseMonths() : 1;
        int occupants = req.occupantCount() != null ? req.occupantCount() : 1;
        int motorbikes = req.motorbikeCount() != null ? req.motorbikeCount() : 0;
        int cars = req.carCount() != null ? req.carCount() : 0;

        // Tính toán toàn bộ chi phí và validate backend bằng RentalCostCalculator
        RentalEstimateResponse estimate = rentalCostCalculator.calculate(
                listing,
                req.moveInDate(),
                leaseMonths,
                occupants,
                motorbikes,
                cars,
                req.depositAmount()
        );

        String costBreakdownJson = null;
        String excludedChargesJson = null;
        try {
            costBreakdownJson = objectMapper.writeValueAsString(estimate.predictableCharges());
            excludedChargesJson = objectMapper.writeValueAsString(estimate.excludedCharges());
        } catch (Exception e) {
            log.error("Failed to serialize charge snapshots for rental request", e);
        }

        RentalRequest rentalRequest = RentalRequest.builder()
                .id(UUID.randomUUID().toString())
                .listing(listing)
                .ownerId(listing.getOwnerId())
                .renterId(renterId)
                .renterName(req.renterName().trim())
                .renterPhone(req.renterPhone().trim())
                .renterEmail(req.renterEmail() != null && !req.renterEmail().isBlank() ? req.renterEmail().trim() : renterEmail)
                .moveInDate(req.moveInDate())
                .leaseMonths(leaseMonths)
                .occupantCount(occupants)
                .motorbikeCount(motorbikes)
                .carCount(cars)
                .monthlyRentPrice(estimate.effectiveMonthlyRent())
                .effectiveMonthlyRent(estimate.effectiveMonthlyRent())
                .estimatedMonthlyCharges(estimate.predictableMonthlyChargesTotal())
                .estimatedMonthlyTotal(estimate.estimatedMonthlyTotal())
                .depositAmount(estimate.depositAmount())
                .estimatedInitialTotal(estimate.estimatedInitialTotal())
                .estimatedLeaseTotal(estimate.estimatedLeaseTotal())
                .costBreakdownSnapshot(costBreakdownJson)
                .excludedChargesSnapshot(excludedChargesJson)
                .renterNote(req.renterNote() != null && !req.renterNote().isBlank() ? req.renterNote().trim() : null)
                .status(RentalRequestStatus.PENDING)
                .build();

        RentalRequest saved = rentalRequestRepository.save(rentalRequest);
        log.info("Renter [{}] submitted rental request [{}] for listing [{}]", renterId, saved.getId(), listing.getId());
        return toResponse(saved);
    }

    // 2. CHỦ NHÀ CHẤP THUẬN YÊU CẦU THUÊ (ACCEPT -> KHÓA BÀI ĐĂNG 24H -> HỦY CÁC YÊU CẦU SONG SONG)
    @Transactional
    public RentalRequestResponse acceptRentalRequest(String ownerId, String requestId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        RentalRequest req = rentalRequestRepository.findById(requestId)
                .orElseThrow(() -> new AppException(ListingErrorCode.RENTAL_REQUEST_NOT_FOUND));

        if (!ownerId.equals(req.getOwnerId())) {
            throw new AppException(ListingErrorCode.RENTAL_REQUEST_FORBIDDEN);
        }

        if (req.getStatus() != RentalRequestStatus.PENDING) {
            throw new AppException(ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS);
        }

        Listing listing = req.getListing();
        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw new AppException(ListingErrorCode.LISTING_ALREADY_RESERVED);
        }

        // PESSIMISTIC LOCK: Khóa bãi xe của chi nhánh hoặc của listing để ngăn chặn race condition overbooking
        if (listing.getBranchId() != null) {
            propertyBranchRepository.findByIdForUpdate(listing.getBranchId())
                    .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));
        } else {
            listingRepository.findByIdForUpdate(listing.getId())
                    .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));
        }

        // Kiểm tra lại slot khả dụng trong khoảng thời gian thuê
        rentalCostCalculator.calculate(
                listing,
                req.getMoveInDate(),
                req.getLeaseMonths(),
                req.getOccupantCount(),
                req.getMotorbikeCount() != null ? req.getMotorbikeCount() : 0,
                req.getCarCount() != null ? req.getCarCount() : 0,
                req.getDepositAmount()
        );

        Instant now = Instant.now();
        Instant holdExpiresAt = now.plus(holdDuration);

        req.setStatus(RentalRequestStatus.ACCEPTED);
        req.setAcceptedAt(now);
        req.setHoldExpiresAt(holdExpiresAt);
        req.setListingSnapshot(buildListingSnapshotJson(listing));
        RentalRequest saved = rentalRequestRepository.save(req);

        // Tạo HELD reservation cho xe máy và ô tô (nếu có số lượng > 0)
        parkingReservationService.createHeldReservations(req, listing, holdExpiresAt);

        // Chuyển bài đăng sang trạng thái RESERVED
        listingStatusService.markReserved(listing, ownerId);

        // Tự động hủy toàn bộ các yêu cầu PENDING khác cho cùng bài đăng này
        List<RentalRequest> otherPending = rentalRequestRepository.findByListingIdAndStatus(listing.getId(), RentalRequestStatus.PENDING);
        for (RentalRequest other : otherPending) {
            if (!other.getId().equals(requestId)) {
                other.setStatus(RentalRequestStatus.CANCELLED_BY_SYSTEM);
                other.setRejectReason("Chủ nhà đã chấp thuận một yêu cầu thuê khác cho bất động sản này.");
                rentalRequestRepository.save(other);

                // Ghi log giả lập gửi email thông báo hủy cho khách
                log.info("[EMAIL_MOCK] To: {} | Subject: Yêu cầu thuê nhà của bạn đã bị hủy | Lý do: Chủ nhà đã chấp thuận khách thuê khác cho bài đăng '{}'",
                        other.getRenterEmail(), listing.getTitle());
            }
        }

        // Tạo INITIAL payment trạng thái AWAITING_TRANSFER trong cùng transaction
        if (rentalPaymentLifecyclePort != null) {
            rentalPaymentLifecyclePort.createInitialPayment(saved, holdExpiresAt);
        }

        log.info("Owner [{}] ACCEPTED rental request [{}]. Listing [{}] marked as RESERVED until [{}]",
                ownerId, requestId, listing.getId(), holdExpiresAt);

        return toResponse(saved);
    }

    // 3. CHỦ NHÀ TỪ CHỐI YÊU CẦU THUÊ
    @Transactional
    public RentalRequestResponse rejectRentalRequest(String ownerId, String requestId, RejectRentalRequest rejectReq) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        RentalRequest req = rentalRequestRepository.findById(requestId)
                .orElseThrow(() -> new AppException(ListingErrorCode.RENTAL_REQUEST_NOT_FOUND));

        if (!ownerId.equals(req.getOwnerId())) {
            throw new AppException(ListingErrorCode.RENTAL_REQUEST_FORBIDDEN);
        }

        if (rentalPaymentLifecyclePort != null && rentalPaymentLifecyclePort.isConfirmed(requestId)) {
            throw new AppException(ListingErrorCode.RENTAL_REQUEST_ALREADY_PAID);
        }

        if (req.getStatus() != RentalRequestStatus.PENDING) {
            throw new AppException(ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS);
        }

        req.setStatus(RentalRequestStatus.REJECTED);
        req.setRejectReason(rejectReq != null && rejectReq.rejectReason() != null && !rejectReq.rejectReason().isBlank()
                ? rejectReq.rejectReason().trim() : "Chủ nhà từ chối yêu cầu thuê");
        RentalRequest saved = rentalRequestRepository.save(req);

        // Hủy pending payment nếu có
        if (rentalPaymentLifecyclePort != null) {
            rentalPaymentLifecyclePort.cancelPayment(requestId, "Chủ nhà từ chối yêu cầu thuê");
        }

        // Giải phóng slot xe (nếu có)
        parkingReservationService.releaseReservationsForRequest(requestId);

        log.info("Owner [{}] REJECTED rental request [{}]", ownerId, requestId);
        return toResponse(saved);
    }

    // 4. KHÁCH HÀNG TỰ HỦY YÊU CẦU THUÊ (KHI ĐANG PENDING)
    @Transactional
    public RentalRequestResponse cancelRentalRequest(String renterId, String requestId) {
        if (renterId == null || renterId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        RentalRequest req = rentalRequestRepository.findById(requestId)
                .orElseThrow(() -> new AppException(ListingErrorCode.RENTAL_REQUEST_NOT_FOUND));

        if (!renterId.equals(req.getRenterId())) {
            throw new AppException(ListingErrorCode.RENTAL_REQUEST_FORBIDDEN);
        }

        if (rentalPaymentLifecyclePort != null && rentalPaymentLifecyclePort.isConfirmed(requestId)) {
            throw new AppException(ListingErrorCode.RENTAL_REQUEST_ALREADY_PAID);
        }

        if (req.getStatus() != RentalRequestStatus.PENDING) {
            throw new AppException(ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS);
        }

        req.setStatus(RentalRequestStatus.CANCELLED_BY_RENTER);
        RentalRequest saved = rentalRequestRepository.save(req);

        // Hủy pending payment nếu có
        if (rentalPaymentLifecyclePort != null) {
            rentalPaymentLifecyclePort.cancelPayment(requestId, "Khách thuê tự hủy yêu cầu thuê");
        }

        // Giải phóng slot xe (nếu có)
        parkingReservationService.releaseReservationsForRequest(requestId);

        log.info("Renter [{}] CANCELLED rental request [{}]", renterId, requestId);
        return toResponse(saved);
    }

    // 5. XEM CHI TIẾT 1 YÊU CẦU THUÊ
    @Transactional(readOnly = true)
    public RentalRequestResponse getRequestById(String id, String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        RentalRequest req = rentalRequestRepository.findById(id)
                .orElseThrow(() -> new AppException(ListingErrorCode.RENTAL_REQUEST_NOT_FOUND));

        if (!actorId.equals(req.getRenterId()) && !actorId.equals(req.getOwnerId())) {
            throw new AppException(ListingErrorCode.RENTAL_REQUEST_FORBIDDEN);
        }

        return toResponse(req);
    }

    // 6. KHÁCH HÀNG XEM CÁC YÊU CẦU CỦA MÌNH
    @Transactional(readOnly = true)
    public PageResponse<RentalRequestResponse> getMyRequests(
            String renterId, RentalRequestStatus status, int page, int size) {
        if (renterId == null || renterId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        Specification<RentalRequest> spec = (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("listing", JoinType.INNER);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("renterId"), renterId));
            predicates.add(cb.isTrue(root.get("active")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var sort = Sort.by(Sort.Order.desc("createdAt"));
        var pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50), sort);
        Page<RentalRequest> pageResult = rentalRequestRepository.findAll(spec, pageable);
        List<String> requestIds = pageResult.getContent().stream().map(RentalRequest::getId).toList();
        Map<String, InitialPaymentSummary> paymentsMap = (requestIds.isEmpty() || rentalPaymentLifecyclePort == null)
                ? Map.of()
                : rentalPaymentLifecyclePort.getInitialPaymentSummaries(requestIds);
        return new PageResponse<>(pageResult.map(r -> toResponse(r, paymentsMap.get(r.getId()))));
    }

    // 7. CHỦ NHÀ XEM DANH SÁCH YÊU CẦU THUÊ ĐƯỢC GỬI ĐẾN
    @Transactional(readOnly = true)
    public PageResponse<RentalRequestResponse> getOwnerRequests(
            String ownerId, String listingId, RentalRequestStatus status, int page, int size) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        Specification<RentalRequest> spec = (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("listing", JoinType.INNER);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("ownerId"), ownerId));
            predicates.add(cb.isTrue(root.get("active")));
            if (listingId != null && !listingId.isBlank()) {
                predicates.add(cb.equal(root.get("listing").get("id"), listingId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var sort = Sort.by(Sort.Order.desc("createdAt"));
        var pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50), sort);
        Page<RentalRequest> pageResult = rentalRequestRepository.findAll(spec, pageable);
        List<String> requestIds = pageResult.getContent().stream().map(RentalRequest::getId).toList();
        Map<String, InitialPaymentSummary> paymentsMap = (requestIds.isEmpty() || rentalPaymentLifecyclePort == null)
                ? Map.of()
                : rentalPaymentLifecyclePort.getInitialPaymentSummaries(requestIds);
        return new PageResponse<>(pageResult.map(r -> toResponse(r, paymentsMap.get(r.getId()))));
    }

    // 8. KIỂM TRA XEM KHÁCH CÓ YÊU CẦU ACTIVE TRÊN BÀI ĐĂNG NÀY KHÔNG
    @Transactional(readOnly = true)
    public RentalRequestResponse getMyRequestByListing(String renterId, String listingId) {
        if (renterId == null || renterId.isBlank() || listingId == null || listingId.isBlank()) {
            return null;
        }

        Optional<RentalRequest> req = rentalRequestRepository.findFirstByListingIdAndRenterIdAndStatusIn(
                listingId, renterId, Set.of(RentalRequestStatus.PENDING, RentalRequestStatus.ACCEPTED));

        return req.map(this::toResponse).orElse(null);
    }

    // 9. CRON JOB: HẾT HẠN GIỮ CHỖ -> TỰ ĐỘNG EXPIRE & TRẢ VỀ PUBLISHED
    @Transactional
    public int expirePendingHoldRequests(Instant now) {
        // Đồng bộ lại holdExpiresAt nếu config rút ngắn (vd. 24h -> 15 phút)
        clampActiveHoldExpiriesToConfig(now);

        List<RentalRequest> expiredList = rentalRequestRepository.findAllByStatusAndHoldExpiresAtLessThanEqual(
                RentalRequestStatus.ACCEPTED, now);

        if (expiredList.isEmpty()) {
            return 0;
        }

        int expiredCount = 0;
        for (RentalRequest req : expiredList) {
            if (isHoldProtected(req.getId()) || (rentalPaymentLifecyclePort != null && rentalPaymentLifecyclePort.isHoldProtected(req.getId()))) {
                log.info("[RENTAL_HOLD_PROTECTED] Request [{}] has protected payment or contract; skip expiration.", req.getId());
                continue;
            }

            boolean wasConfirmed = rentalPaymentLifecyclePort != null && rentalPaymentLifecyclePort.isConfirmed(req.getId());

            if (rentalPaymentLifecyclePort != null) {
                rentalPaymentLifecyclePort.handleHoldExpired(req.getId());
            }

            req.setStatus(RentalRequestStatus.EXPIRED);
            rentalRequestRepository.save(req);
            expiredCount++;

            // Giải phóng/hết hạn reservation
            parkingReservationService.expireReservationsForRequest(req.getId());

            Listing listing = req.getListing();
            if (listing != null && listing.getStatus() == ListingStatus.RESERVED) {
                String reason = wasConfirmed
                        ? "Hết hạn chuẩn bị hợp đồng sau khi khoản thanh toán ban đầu đã được xác nhận"
                        : "Hết hạn giữ chỗ mà không hoàn tất thủ tục thuê";
                listingStatusService.releaseReserved(listing, "SYSTEM", reason);
                log.info("[RENTAL_HOLD_EXPIRED] Rental request [{}] expired. Listing [{}] returned to PUBLISHED.",
                        req.getId(), listing.getId());
            }
        }

        return expiredCount;
    }

    private boolean isHoldProtected(String rentalRequestId) {
        return holdProtectionCheckers.stream()
                .anyMatch(checker -> checker.isProtected(rentalRequestId));
    }

    /**
     * Nếu thời hạn giữ chỗ cấu hình ngắn hơn mốc đã lưu (sau khi đổi config),
     * cắt holdExpiresAt về acceptedAt + holdDuration (hoặc now nếu đã quá hạn theo config mới).
     * KHÔNG cắt ngắn nếu yêu cầu đã thanh toán ban đầu (đang được bảo vệ tới contractDueAt) hoặc có contract bảo vệ.
     */
    private void clampActiveHoldExpiriesToConfig(Instant now) {
        List<RentalRequest> activeHolds =
                rentalRequestRepository.findAllByStatusAndHoldExpiresAtIsNotNull(RentalRequestStatus.ACCEPTED);
        for (RentalRequest req : activeHolds) {
            if (isHoldProtected(req.getId()) || (rentalPaymentLifecyclePort != null && (rentalPaymentLifecyclePort.isConfirmed(req.getId()) || rentalPaymentLifecyclePort.isHoldProtected(req.getId())))) {
                continue;
            }
            Instant acceptedAt = req.getAcceptedAt() != null ? req.getAcceptedAt() : now;
            Instant maxExpiry = acceptedAt.plus(holdDuration);
            Instant currentExpiry = req.getHoldExpiresAt();
            if (currentExpiry != null && currentExpiry.isAfter(maxExpiry)) {
                req.setHoldExpiresAt(maxExpiry);
                rentalRequestRepository.save(req);
                log.info("[RENTAL_HOLD_CLAMP] Request [{}] holdExpiresAt {} -> {} (config={})",
                        req.getId(), currentExpiry, maxExpiry, holdDuration);
            }
        }
    }

    // MAPPER
    public RentalRequestResponse toResponse(RentalRequest r) {
        InitialPaymentSummary paymentSummary = rentalPaymentLifecyclePort != null
                ? rentalPaymentLifecyclePort.getInitialPaymentSummary(r.getId())
                : null;
        return toResponse(r, paymentSummary);
    }

    private RentalRequestResponse toResponse(RentalRequest r, InitialPaymentSummary paymentSummary) {
        Listing l = r.getListing();
        String thumbnail = null;
        String addressStr = null;
        BigDecimal price = null;

        if (l != null) {
            price = l.getPriceAmount();
            if (l.getMedia() != null && !l.getMedia().isEmpty()) {
                ListingMedia coverMedia = l.getMedia().stream()
                        .filter(ListingMedia::isCover)
                        .findFirst()
                        .orElseGet(() -> l.getMedia().get(0));

                if (coverMedia != null) {
                    if (coverMedia.getStorageObject() != null && coverMedia.getStorageObject().getVisibility() == StorageVisibility.PUBLIC) {
                        thumbnail = "https://%s.s3.%s.amazonaws.com/%s"
                                .formatted(coverMedia.getStorageObject().getBucketName(),
                                        storageProperties.region(),
                                        coverMedia.getStorageObject().getObjectKey());
                    } else if (coverMedia.getMediaUrl() != null && (coverMedia.getMediaUrl().startsWith("http://") || coverMedia.getMediaUrl().startsWith("https://") || coverMedia.getMediaUrl().startsWith("/"))) {
                        thumbnail = coverMedia.getMediaUrl();
                    }
                }
            }
            Address addr = addressRepository.findByListingIdAndActiveTrue(l.getId()).orElse(null);
            if (addr != null) {
                addressStr = addr.getFullAddress();
            }
        }

        return RentalRequestResponse.builder()
                .id(r.getId())
                .listingId(l != null ? l.getId() : null)
                .listingTitle(l != null ? l.getTitle() : null)
                .listingAddress(addressStr)
                .listingThumbnail(thumbnail)
                .listingPrice(price)
                .ownerId(r.getOwnerId())
                .renterId(r.getRenterId())
                .renterName(r.getRenterName())
                .renterPhone(r.getRenterPhone())
                .renterEmail(r.getRenterEmail())
                .moveInDate(r.getMoveInDate())
                .leaseMonths(r.getLeaseMonths())
                .occupantCount(r.getOccupantCount())
                .motorbikeCount(r.getMotorbikeCount() != null ? r.getMotorbikeCount() : 0)
                .carCount(r.getCarCount() != null ? r.getCarCount() : 0)
                .monthlyRentPrice(r.getMonthlyRentPrice())
                .effectiveMonthlyRent(r.getEffectiveMonthlyRent() != null ? r.getEffectiveMonthlyRent() : r.getMonthlyRentPrice())
                .estimatedMonthlyCharges(r.getEstimatedMonthlyCharges())
                .estimatedMonthlyTotal(r.getEstimatedMonthlyTotal())
                .depositAmount(r.getDepositAmount())
                .estimatedInitialTotal(r.getEstimatedInitialTotal())
                .estimatedLeaseTotal(r.getEstimatedLeaseTotal())
                .costBreakdownSnapshot(r.getCostBreakdownSnapshot())
                .excludedChargesSnapshot(r.getExcludedChargesSnapshot())
                .listingSnapshot(r.getListingSnapshot())
                .renterNote(r.getRenterNote())
                .status(r.getStatus())
                .rejectReason(r.getRejectReason())
                .acceptedAt(r.getAcceptedAt())
                .holdExpiresAt(r.getHoldExpiresAt())
                .initialPayment(paymentSummary)
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }

    public String buildListingSnapshotJson(Listing listing) {
        if (listing == null) return null;
        try {
            ListingSnapshotDto dto = buildListingSnapshotDto(listing);
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            log.error("Failed to build listing snapshot for listingId={}", listing.getId(), e);
            return null;
        }
    }

    public ListingSnapshotDto buildListingSnapshotDto(Listing listing) {
        if (listing == null) return null;

        PropertyBranch branch = null;
        if (listing.getBranchId() != null) {
            branch = propertyBranchRepository.findById(listing.getBranchId()).orElse(null);
        }

        String unitNumber = resolveUnitNumber(listing);
        String floor = resolveFloor(listing);
        String buildingName = resolveBuildingName(listing);
        String baseAddress = addressRepository.findByListingIdAndActiveTrue(listing.getId())
                .map(Address::getFullAddress)
                .orElse("");
        if (baseAddress.isBlank() && branch != null && branch.getAddress() != null) {
            baseAddress = branch.getAddress().getFullAddress() != null ? branch.getAddress().getFullAddress() : "";
        }
        String fullAddress = joinNonBlank(", ", unitNumber, floor, buildingName, baseAddress);

        String buildingRules = null;
        if (branch != null && branch.getBuildingRules() != null && !branch.getBuildingRules().isBlank()) {
            buildingRules = branch.getBuildingRules().trim();
        }

        List<ListingSnapshotDto.AmenityItemSnapshot> amenitySnapshots = new ArrayList<>();
        Set<String> seenAmenityCodes = new HashSet<>();
        if (listing.getAmenities() != null) {
            for (Amenity a : listing.getAmenities()) {
                if (a != null && a.getCode() != null && seenAmenityCodes.add(a.getCode().toUpperCase())) {
                    amenitySnapshots.add(ListingSnapshotDto.AmenityItemSnapshot.builder()
                            .code(a.getCode())
                            .name(a.getName())
                            .scope("Riêng trong căn/phòng/nhà")
                            .sourceType("LISTING")
                            .build());
                }
            }
        }
        if (branch != null && branch.getBuildingAmenities() != null) {
            for (Amenity a : branch.getBuildingAmenities()) {
                if (a != null && a.getCode() != null && seenAmenityCodes.add(a.getCode().toUpperCase())) {
                    amenitySnapshots.add(ListingSnapshotDto.AmenityItemSnapshot.builder()
                            .code(a.getCode())
                            .name(a.getName())
                            .scope("Dùng chung")
                            .sourceType("SHARED_PROPERTY")
                            .build());
                }
            }
        }

        List<String> customAmenities = new ArrayList<>();
        if (listing.getCustomAmenities() != null) {
            for (ListingCustomAmenity ca : listing.getCustomAmenities()) {
                if (ca != null && ca.getName() != null && !ca.getName().isBlank()) {
                    customAmenities.add(ca.getName().trim());
                }
            }
        }

        List<ListingSnapshotDto.FurnishingItemSnapshot> furnishingSnapshots = new ArrayList<>();
        if (listing.getFurnishings() != null) {
            List<ListingFurnishingAsset> sorted = new ArrayList<>(listing.getFurnishings());
            sorted.sort(Comparator.comparing(f -> f.getSortOrder() != null ? f.getSortOrder() : Integer.MAX_VALUE));
            for (ListingFurnishingAsset f : sorted) {
                furnishingSnapshots.add(ListingSnapshotDto.FurnishingItemSnapshot.builder()
                        .itemCode(f.getItemCode())
                        .assetName(f.getAssetName())
                        .quantity(f.getQuantity() != null ? f.getQuantity() : 1)
                        .handoverCondition(f.getHandoverCondition() != null ? f.getHandoverCondition().name() : "GOOD")
                        .conditionNote(f.getConditionNote())
                        .sortOrder(f.getSortOrder())
                        .build());
            }
        }

        List<ListingSnapshotDto.ChargeItemSnapshot> chargeSnapshots = new ArrayList<>();
        if (listing.getCharges() != null) {
            List<ListingCharge> sorted = new ArrayList<>(listing.getCharges());
            sorted.sort(Comparator.comparing(c -> c.getSortOrder() != null ? c.getSortOrder() : Integer.MAX_VALUE));
            for (ListingCharge c : sorted) {
                chargeSnapshots.add(ListingSnapshotDto.ChargeItemSnapshot.builder()
                        .chargeType(c.getChargeType() != null ? c.getChargeType().name() : null)
                        .billingMethod(c.getBillingMethod() != null ? c.getBillingMethod().name() : null)
                        .amount(c.getAmount())
                        .currency(c.getCurrency())
                        .unit(c.getUnit())
                        .includedInRent(c.isIncludedInRent())
                        .customName(c.getCustomName())
                        .description(c.getDescription())
                        .sortOrder(c.getSortOrder())
                        .build());
            }
        }

        ListingSnapshotDto.ApartmentDetailSnapshot aptSnapshot = null;
        if (listing.getApartmentDetail() != null) {
            ListingApartmentDetail apt = listing.getApartmentDetail();
            aptSnapshot = ListingSnapshotDto.ApartmentDetailSnapshot.builder()
                    .projectName(apt.getProjectName())
                    .buildingBlock(apt.getBuildingBlock())
                    .unitCode(apt.getUnitCode())
                    .floorNumber(apt.getFloorNumber())
                    .buildingTotalFloors(apt.getBuildingTotalFloors())
                    .bedroomCount(apt.getBedroomCount())
                    .bathroomCount(apt.getBathroomCount())
                    .livingRoomCount(apt.getLivingRoomCount())
                    .kitchenCount(apt.getKitchenCount())
                    .furnishingStatus(apt.getFurnishingStatus() != null ? apt.getFurnishingStatus().name() : null)
                    .mainDoorDirection(apt.getMainDoorDirection())
                    .balconyDirection(apt.getBalconyDirection())
                    .viewDescription(apt.getViewDescription())
                    .maxOccupants(apt.getMaxOccupants())
                    .legalStatus(apt.getLegalStatus())
                    .build();
        }

        ListingSnapshotDto.HouseDetailSnapshot houseSnapshot = null;
        if (listing.getHouseDetail() != null) {
            ListingHouseDetail h = listing.getHouseDetail();
            houseSnapshot = ListingSnapshotDto.HouseDetailSnapshot.builder()
                    .landAreaM2(h.getLandAreaM2())
                    .frontageWidthM(h.getFrontageWidthM())
                    .lengthM(h.getLengthM())
                    .accessRoadWidthM(h.getAccessRoadWidthM())
                    .frontageCount(h.getFrontageCount())
                    .totalFloors(h.getTotalFloors())
                    .bedroomCount(h.getBedroomCount())
                    .bathroomCount(h.getBathroomCount())
                    .livingRoomCount(h.getLivingRoomCount())
                    .kitchenCount(h.getKitchenCount())
                    .hasRooftop(h.getHasRooftop())
                    .hasGarage(h.getHasGarage())
                    .accessType(h.getAccessType())
                    .maxOccupants(h.getMaxOccupants())
                    .maxVehicles(h.getMaxVehicles())
                    .furnishingStatus(h.getFurnishingStatus() != null ? h.getFurnishingStatus().name() : null)
                    .legalStatus(h.getLegalStatus())
                    .rentalScopeDescription(h.getRentalScopeDescription())
                    .rentedFloorFrom(h.getRentedFloorFrom())
                    .rentedFloorTo(h.getRentedFloorTo())
                    .build();
        }

        ListingSnapshotDto.RoomDetailSnapshot roomSnapshot = null;
        if (listing.getRoomDetail() != null) {
            ListingRoomDetail r = listing.getRoomDetail();
            roomSnapshot = ListingSnapshotDto.RoomDetailSnapshot.builder()
                    .roomCode(r.getRoomCode())
                    .floorNumber(r.getFloorNumber())
                    .restroomType(r.getRestroomType() != null ? r.getRestroomType().name() : null)
                    .kitchenType(r.getKitchenType() != null ? r.getKitchenType().name() : null)
                    .hasWindow(r.getHasWindow())
                    .balconyType(r.resolvedBalconyType() != null ? r.resolvedBalconyType().name() : null)
                    .hasMezzanine(r.getHasMezzanine())
                    .furnishingStatus(r.getFurnishingStatus() != null ? r.getFurnishingStatus().name() : null)
                    .accessType(r.getAccessType() != null ? r.getAccessType().name() : null)
                    .accessHoursType(r.getAccessHoursType() != null ? r.getAccessHoursType().name() : null)
                    .electricMeterType(r.getElectricMeterType() != null ? r.getElectricMeterType().name() : null)
                    .waterMeterType(r.getWaterMeterType() != null ? r.getWaterMeterType().name() : null)
                    .maxOccupants(r.getMaxOccupants())
                    .maxVehicles(r.getMaxVehicles())
                    .parkingPolicy(r.getParkingPolicy() != null ? r.getParkingPolicy().name() : null)
                    .build();
        }

        String scopeDesc = null;
        if (listing.getHouseDetail() != null && listing.getHouseDetail().getRentalScopeDescription() != null) {
            scopeDesc = listing.getHouseDetail().getRentalScopeDescription();
        } else if (listing.getCategory() == ListingCategory.ROOM) {
            scopeDesc = "Thuê phòng trọ khép kín / dùng chung";
        } else if (listing.getCategory() == ListingCategory.APARTMENT) {
            scopeDesc = "Thuê toàn bộ căn hộ chung cư";
        } else if (listing.getCategory() == ListingCategory.HOUSE) {
            scopeDesc = "Thuê toàn bộ nhà nguyên căn";
        }

        Integer maxVehicles = null;
        if (listing.getRoomDetail() != null && listing.getRoomDetail().getMaxVehicles() != null) {
            maxVehicles = listing.getRoomDetail().getMaxVehicles();
        } else if (listing.getHouseDetail() != null && listing.getHouseDetail().getMaxVehicles() != null) {
            maxVehicles = listing.getHouseDetail().getMaxVehicles();
        } else if (listing.getMaxMotorbikeCount() != null || listing.getMaxCarCount() != null) {
            int total = (listing.getMaxMotorbikeCount() != null ? listing.getMaxMotorbikeCount() : 0)
                      + (listing.getMaxCarCount() != null ? listing.getMaxCarCount() : 0);
            if (total > 0) maxVehicles = total;
        }

        return ListingSnapshotDto.builder()
                .schemaVersion(1)
                .listingId(listing.getId())
                .listingCode(listing.getId() != null && listing.getId().length() >= 8 ? listing.getId().substring(0, 8).toUpperCase() : listing.getId())
                .category(listing.getCategory())
                .categoryLabel(listing.getCategory() != null ? listing.getCategory().name() : "")
                .title(listing.getTitle())
                .fullAddress(fullAddress)
                .unitNumber(unitNumber)
                .floor(floor)
                .buildingName(buildingName)
                .areaM2(listing.getAreaM2())
                .priceAmount(listing.getPriceAmount())
                .currency(listing.getCurrency())
                .priceUnit(listing.getPriceUnit() != null ? listing.getPriceUnit().name() : "MONTH")
                .paymentCycle(listing.getPaymentCycle() != null ? listing.getPaymentCycle().name() : "MONTHLY")
                .depositType(listing.getDepositType() != null ? listing.getDepositType().name() : "ONE_MONTH")
                .depositAmount(listing.getDepositAmount())
                .depositMonths(listing.getDepositMonths())
                .minimumLeaseMonths(listing.getMinimumLeaseMonths())
                .vatIncluded(listing.getVatIncluded())
                .managementFeeIncluded(listing.isManagementFeeIncluded())
                .maxOccupants(listing.getApartmentDetail() != null ? listing.getApartmentDetail().getMaxOccupants() :
                        (listing.getRoomDetail() != null ? listing.getRoomDetail().getMaxOccupants() :
                                (listing.getHouseDetail() != null ? listing.getHouseDetail().getMaxOccupants() : null)))
                .maxVehicles(maxVehicles)
                .maxMotorbikeCount(listing.getMaxMotorbikeCount())
                .maxCarCount(listing.getMaxCarCount())
                .rentalScopeDescription(scopeDesc)
                .apartmentDetail(aptSnapshot)
                .houseDetail(houseSnapshot)
                .roomDetail(roomSnapshot)
                .buildingRules(buildingRules)
                .amenities(amenitySnapshots)
                .customAmenities(customAmenities)
                .furnishings(furnishingSnapshots)
                .charges(chargeSnapshots)
                .capturedAt(Instant.now())
                .build();
    }

    private static String resolveUnitNumber(Listing listing) {
        if (listing.getApartmentDetail() != null && isNotBlank(listing.getApartmentDetail().getUnitCode())) {
            return "Căn hộ " + listing.getApartmentDetail().getUnitCode().trim();
        }
        if (listing.getRoomDetail() != null && isNotBlank(listing.getRoomDetail().getRoomCode())) {
            return "Phòng " + listing.getRoomDetail().getRoomCode().trim();
        }
        return "";
    }

    private static String resolveFloor(Listing listing) {
        Integer floor = null;
        if (listing.getApartmentDetail() != null) {
            floor = listing.getApartmentDetail().getFloorNumber();
        } else if (listing.getRoomDetail() != null) {
            floor = listing.getRoomDetail().getFloorNumber();
        } else if (listing.getHouseDetail() != null && listing.getHouseDetail().getTotalFloors() != null) {
            return "Nhà " + listing.getHouseDetail().getTotalFloors() + " tầng";
        }
        return floor != null ? "Tầng " + floor : "";
    }

    private static String resolveBuildingName(Listing listing) {
        if (listing.getApartmentDetail() != null) {
            return joinNonBlank(" ",
                    nullToEmpty(listing.getApartmentDetail().getProjectName()),
                    nullToEmpty(listing.getApartmentDetail().getBuildingBlock()));
        }
        return "";
    }

    private static String joinNonBlank(String separator, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (isNotBlank(part)) {
                kept.add(part.trim());
            }
        }
        return String.join(separator, kept);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
