package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.PageResponse;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.CreateRentalRequest;
import com.hs.listing.dto.request.RejectRentalRequest;
import com.hs.listing.dto.response.RentalRequestResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingMedia;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.DepositType;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
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
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RentalRequestService {

    private final RentalRequestRepository rentalRequestRepository;
    private final ListingRepository listingRepository;
    private final ListingStatusService listingStatusService;
    private final AddressRepository addressRepository;
    private final StorageProperties storageProperties;
    private final Duration holdDuration;

    @Autowired
    public RentalRequestService(
            RentalRequestRepository rentalRequestRepository,
            ListingRepository listingRepository,
            ListingStatusService listingStatusService,
            AddressRepository addressRepository,
            StorageProperties storageProperties,
            @Value("${rental.hold-duration-hours}") double holdDurationHours) {
        this.rentalRequestRepository = rentalRequestRepository;
        this.listingRepository = listingRepository;
        this.listingStatusService = listingStatusService;
        this.addressRepository = addressRepository;
        this.storageProperties = storageProperties;

        long millis = Math.round(holdDurationHours * 3600.0 * 1000.0);
        this.holdDuration = Duration.ofMillis(Math.max(millis, 1000L));
    }

    public RentalRequestService(
            RentalRequestRepository rentalRequestRepository,
            ListingRepository listingRepository,
            ListingStatusService listingStatusService,
            AddressRepository addressRepository,
            StorageProperties storageProperties,
            int holdDurationHours) {
        this(rentalRequestRepository, listingRepository, listingStatusService, addressRepository, storageProperties, (double) holdDurationHours);
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

        // Tính toán tiền cọc hợp lệ theo DepositType của bài đăng
        BigDecimal effectiveDepositAmount;
        if (listing.getDepositType() == DepositType.NONE) {
            effectiveDepositAmount = BigDecimal.ZERO;
        } else if (listing.getDepositType() == DepositType.MONTH_COUNT) {
            int months = listing.getDepositMonths() != null && listing.getDepositMonths() > 0
                    ? listing.getDepositMonths() : 1;
            effectiveDepositAmount = listing.getPriceAmount() != null
                    ? listing.getPriceAmount().multiply(BigDecimal.valueOf(months))
                    : BigDecimal.ZERO;
        } else if (listing.getDepositType() == DepositType.FIXED_AMOUNT) {
            effectiveDepositAmount = listing.getDepositAmount() != null
                    ? listing.getDepositAmount() : BigDecimal.ZERO;
        } else if (listing.getDepositType() == DepositType.NEGOTIABLE) {
            // Thỏa thuận: nhận mức cọc do người thuê đề xuất (nếu có)
            effectiveDepositAmount = req.depositAmount();
        } else {
            effectiveDepositAmount = listing.getDepositAmount() != null
                    ? listing.getDepositAmount() : listing.getPriceAmount();
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
                .leaseMonths(req.leaseMonths())
                .occupantCount(req.occupantCount() != null ? req.occupantCount() : 1)
                .monthlyRentPrice(listing.getPriceAmount())
                .depositAmount(effectiveDepositAmount)
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

        Instant now = Instant.now();
        Instant holdExpiresAt = now.plus(holdDuration);

        req.setStatus(RentalRequestStatus.ACCEPTED);
        req.setAcceptedAt(now);
        req.setHoldExpiresAt(holdExpiresAt);
        RentalRequest saved = rentalRequestRepository.save(req);

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

        if (req.getStatus() != RentalRequestStatus.PENDING) {
            throw new AppException(ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS);
        }

        req.setStatus(RentalRequestStatus.REJECTED);
        req.setRejectReason(rejectReq != null && rejectReq.rejectReason() != null && !rejectReq.rejectReason().isBlank()
                ? rejectReq.rejectReason().trim() : "Chủ nhà từ chối yêu cầu thuê");
        RentalRequest saved = rentalRequestRepository.save(req);

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

        if (req.getStatus() != RentalRequestStatus.PENDING) {
            throw new AppException(ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS);
        }

        req.setStatus(RentalRequestStatus.CANCELLED_BY_RENTER);
        RentalRequest saved = rentalRequestRepository.save(req);

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
        return new PageResponse<>(pageResult.map(this::toResponse));
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
        return new PageResponse<>(pageResult.map(this::toResponse));
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

    // 9. CRON JOB: HẾT HẠN GIỮ CHỖ 24H -> TỰ ĐỘNG EXPIRE & TRẢ VỀ PUBLISHED
    @Transactional
    public int expirePendingHoldRequests(Instant now) {
        List<RentalRequest> expiredList = rentalRequestRepository.findAllByStatusAndHoldExpiresAtLessThanEqual(
                RentalRequestStatus.ACCEPTED, now);

        if (expiredList.isEmpty()) {
            return 0;
        }

        for (RentalRequest req : expiredList) {
            req.setStatus(RentalRequestStatus.EXPIRED);
            rentalRequestRepository.save(req);

            Listing listing = req.getListing();
            if (listing != null && listing.getStatus() == ListingStatus.RESERVED) {
                listingStatusService.releaseReserved(listing, "SYSTEM", "Hết hạn giữ chỗ 24 giờ mà không hoàn tất thủ tục thuê");
                log.info("[RENTAL_HOLD_EXPIRED] Rental request [{}] expired. Listing [{}] returned to PUBLISHED.",
                        req.getId(), listing.getId());
            }
        }

        return expiredList.size();
    }

    // MAPPER
    private RentalRequestResponse toResponse(RentalRequest r) {
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
                .monthlyRentPrice(r.getMonthlyRentPrice())
                .depositAmount(r.getDepositAmount())
                .renterNote(r.getRenterNote())
                .status(r.getStatus())
                .rejectReason(r.getRejectReason())
                .acceptedAt(r.getAcceptedAt())
                .holdExpiresAt(r.getHoldExpiresAt())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
