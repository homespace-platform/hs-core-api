package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.response.InitialPaymentSummary;
import com.hs.listing.dto.response.RentalPaymentResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalPayment;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalPaymentStatus;
import com.hs.listing.model.constant.RentalPaymentType;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalPaymentRepository;
import com.hs.listing.repository.RentalRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class RentalPaymentService {

    private final RentalPaymentRepository rentalPaymentRepository;
    private final RentalRequestRepository rentalRequestRepository;
    private final ParkingReservationService parkingReservationService;
    private final Duration contractPreparationDuration;

    public RentalPaymentService(
            RentalPaymentRepository rentalPaymentRepository,
            RentalRequestRepository rentalRequestRepository,
            ParkingReservationService parkingReservationService,
            @Value("${rental.contract-preparation-duration:24h}") String contractPrepDurationStr) {
        this.rentalPaymentRepository = rentalPaymentRepository;
        this.rentalRequestRepository = rentalRequestRepository;
        this.parkingReservationService = parkingReservationService;
        this.contractPreparationDuration = parseDuration(contractPrepDurationStr);
    }

    private static Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ofHours(24);
        }
        String trimmed = raw.trim().toLowerCase();
        try {
            if (trimmed.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            } else if (trimmed.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            } else if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            } else if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            }
            return Duration.parse(raw);
        } catch (Exception e) {
            log.warn("Could not parse duration '{}', fallback to 24h: {}", raw, e.getMessage());
            return Duration.ofHours(24);
        }
    }

    /**
     * Tạo INITIAL_PAYMENT PENDING từ snapshot của RentalRequest khi owner accept request.
     */
    @Transactional
    public RentalPayment createInitialPayment(RentalRequest req, Instant expiresAt) {
        Optional<RentalPayment> existing = rentalPaymentRepository.findByRentalRequestIdAndType(
                req.getId(), RentalPaymentType.INITIAL_PAYMENT);
        if (existing.isPresent()) {
            log.info("Rental payment already exists for request [{}], returning existing", req.getId());
            return existing.get();
        }

        BigDecimal monthlyRent = req.getEffectiveMonthlyRent() != null
                ? req.getEffectiveMonthlyRent()
                : (req.getMonthlyRentPrice() != null ? req.getMonthlyRentPrice() : BigDecimal.ZERO);
        BigDecimal monthlyCharges = req.getEstimatedMonthlyCharges() != null
                ? req.getEstimatedMonthlyCharges()
                : BigDecimal.ZERO;
        BigDecimal depositAmount = req.getDepositAmount() != null
                ? req.getDepositAmount()
                : BigDecimal.ZERO;
        BigDecimal totalAmount = req.getEstimatedInitialTotal() != null
                ? req.getEstimatedInitialTotal()
                : monthlyRent.add(monthlyCharges).add(depositAmount);

        RentalPayment payment = RentalPayment.builder()
                .rentalRequestId(req.getId())
                .listingId(req.getListing() != null ? req.getListing().getId() : "")
                .renterId(req.getRenterId())
                .ownerId(req.getOwnerId())
                .type(RentalPaymentType.INITIAL_PAYMENT)
                .status(RentalPaymentStatus.PENDING)
                .currency("VND")
                .monthlyRent(monthlyRent)
                .monthlyCharges(monthlyCharges)
                .depositAmount(depositAmount)
                .totalAmount(totalAmount)
                .costBreakdownSnapshot(req.getCostBreakdownSnapshot())
                .excludedChargesSnapshot(req.getExcludedChargesSnapshot())
                .expiresAt(expiresAt)
                .build();

        RentalPayment saved = rentalPaymentRepository.save(payment);
        log.info("Created INITIAL_PAYMENT id [{}] for rental request [{}] with amount [{}], expires at [{}]",
                saved.getId(), req.getId(), totalAmount, expiresAt);
        return saved;
    }

    /**
     * Lấy thông tin INITIAL_PAYMENT của rental request.
     * Cho phép renter và owner xem.
     * Tự động lazy-create nếu là request cũ đã ACCEPTED nhưng chưa có payment.
     */
    @Transactional
    public RentalPaymentResponse getInitialPayment(String rentalRequestId, String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        RentalRequest request = rentalRequestRepository.findById(rentalRequestId)
                .orElseThrow(() -> new AppException(ListingErrorCode.RENTAL_REQUEST_NOT_FOUND));

        if (!actorId.equals(request.getRenterId()) && !actorId.equals(request.getOwnerId()) && !"system".equals(actorId)) {
            throw new AppException(ListingErrorCode.RENTAL_PAYMENT_FORBIDDEN);
        }

        RentalPayment payment = rentalPaymentRepository.findByRentalRequestIdAndType(
                rentalRequestId, RentalPaymentType.INITIAL_PAYMENT)
                .orElseGet(() -> {
                    // Backward compatibility: Lazy-create cho request ACCEPTED hoặc COMPLETED cũ
                    if (request.getStatus() == RentalRequestStatus.ACCEPTED || request.getStatus() == RentalRequestStatus.COMPLETED) {
                        Instant expiry = request.getHoldExpiresAt() != null ? request.getHoldExpiresAt() : Instant.now().plus(contractPreparationDuration);
                        RentalPayment created = createInitialPayment(request, expiry);
                        if (request.getStatus() == RentalRequestStatus.COMPLETED) {
                            created.setStatus(RentalPaymentStatus.PAID_MOCK);
                            created.setPaidAt(request.getAcceptedAt() != null ? request.getAcceptedAt() : Instant.now());
                            return rentalPaymentRepository.save(created);
                        }
                        return created;
                    }
                    throw new AppException(ListingErrorCode.RENTAL_PAYMENT_NOT_FOUND);
                });

        return toResponse(payment);
    }

    /**
     * Khách hàng thanh toán giả lập (Mock Payment) cho INITIAL_PAYMENT.
     * - Chỉ renter được phép gọi
     * - Request phải ACCEPTED
     * - Listing phải RESERVED
     * - Payment phải PENDING và chưa hết hạn
     * - Idempotency: nếu đã PAID_MOCK hoặc PAID, trả về kết quả hiện tại
     */
    @Transactional
    public RentalPaymentResponse payMock(String rentalRequestId, String renterId) {
        if (renterId == null || renterId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        // PESSIMISTIC LOCK: Khóa RentalPayment và RentalRequest
        RentalPayment payment = rentalPaymentRepository.findByRentalRequestIdAndTypeForUpdate(
                rentalRequestId, RentalPaymentType.INITIAL_PAYMENT)
                .orElseGet(() -> {
                    RentalRequest req = rentalRequestRepository.findById(rentalRequestId)
                            .orElseThrow(() -> new AppException(ListingErrorCode.RENTAL_REQUEST_NOT_FOUND));
                    if (req.getStatus() == RentalRequestStatus.ACCEPTED) {
                        Instant expiry = req.getHoldExpiresAt() != null ? req.getHoldExpiresAt() : Instant.now().plus(contractPreparationDuration);
                        return createInitialPayment(req, expiry);
                    }
                    throw new AppException(ListingErrorCode.RENTAL_PAYMENT_NOT_FOUND);
                });

        RentalRequest request = rentalRequestRepository.findByIdForUpdate(rentalRequestId)
                .orElseThrow(() -> new AppException(ListingErrorCode.RENTAL_REQUEST_NOT_FOUND));

        // 1. Quyền gọi: chỉ renter
        if (!renterId.equals(payment.getRenterId()) || !renterId.equals(request.getRenterId())) {
            throw new AppException(ListingErrorCode.RENTAL_PAYMENT_FORBIDDEN);
        }

        // 2. Idempotency: nếu đã thanh toán trước đó, trả về thành công không trừ tiền lần 2
        if (payment.getStatus() == RentalPaymentStatus.PAID_MOCK || payment.getStatus() == RentalPaymentStatus.PAID) {
            log.info("Idempotent retry: payment [{}] for request [{}] was already paid at [{}]",
                    payment.getId(), rentalRequestId, payment.getPaidAt());
            return toResponse(payment);
        }

        // 3. Trạng thái thanh toán không hợp lệ
        if (payment.getStatus() == RentalPaymentStatus.CANCELLED
                || payment.getStatus() == RentalPaymentStatus.EXPIRED
                || payment.getStatus() == RentalPaymentStatus.REFUNDED) {
            throw new AppException(ListingErrorCode.RENTAL_PAYMENT_NOT_ALLOWED);
        }

        // 4. Request phải là ACCEPTED
        if (request.getStatus() != RentalRequestStatus.ACCEPTED) {
            throw new AppException(ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS);
        }

        // 5. Listing phải đang RESERVED
        Listing listing = request.getListing();
        if (listing == null || listing.getStatus() != ListingStatus.RESERVED) {
            throw new AppException(ListingErrorCode.RENTAL_PAYMENT_NOT_ALLOWED);
        }

        // 6. Kiểm tra hết hạn thanh toán
        Instant now = Instant.now();
        if (payment.getExpiresAt() != null && now.isAfter(payment.getExpiresAt())) {
            payment.setStatus(RentalPaymentStatus.EXPIRED);
            rentalPaymentRepository.save(payment);
            throw new AppException(ListingErrorCode.RENTAL_PAYMENT_EXPIRED);
        }

        // 7. Thực hiện thanh toán giả lập thành công
        Instant contractDueAt = now.plus(contractPreparationDuration);

        payment.setStatus(RentalPaymentStatus.PAID_MOCK);
        payment.setPaidAt(now);
        payment.setContractDueAt(contractDueAt);
        payment.setProvider("MOCK");
        payment.setProviderTransactionId("TXN-MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        RentalPayment savedPayment = rentalPaymentRepository.save(payment);

        // 8. Cập nhật RentalRequest deadline và giữ chỗ tới contractDueAt
        request.setHoldExpiresAt(contractDueAt);
        rentalRequestRepository.save(request);

        // 9. Gia hạn ParkingReservation HELD tới contractDueAt
        parkingReservationService.extendHeldReservations(rentalRequestId, contractDueAt);

        log.info("[RENTAL_PAYMENT_SUCCESS] Initial payment [{}] completed for request [{}] by renter [{}]. Amount: {}, contractDueAt: {}",
                savedPayment.getId(), rentalRequestId, renterId, savedPayment.getTotalAmount(), contractDueAt);

        return toResponse(savedPayment);
    }

    @Transactional(readOnly = true)
    public boolean isPaid(String rentalRequestId) {
        return rentalPaymentRepository.existsByRentalRequestIdAndStatusIn(
                rentalRequestId, Set.of(RentalPaymentStatus.PAID_MOCK, RentalPaymentStatus.PAID));
    }

    @Transactional(readOnly = true)
    public InitialPaymentSummary getInitialPaymentSummary(String rentalRequestId) {
        return rentalPaymentRepository.findByRentalRequestIdAndType(
                rentalRequestId, RentalPaymentType.INITIAL_PAYMENT)
                .map(this::toSummary)
                .orElse(null);
    }

    public InitialPaymentSummary toSummary(RentalPayment p) {
        if (p == null) return null;
        return InitialPaymentSummary.builder()
                .id(p.getId())
                .status(p.getStatus())
                .totalAmount(p.getTotalAmount())
                .expiresAt(p.getExpiresAt())
                .paidAt(p.getPaidAt())
                .contractDueAt(p.getContractDueAt())
                .build();
    }

    public RentalPaymentResponse toResponse(RentalPayment p) {
        if (p == null) return null;
        return RentalPaymentResponse.builder()
                .id(p.getId())
                .rentalRequestId(p.getRentalRequestId())
                .listingId(p.getListingId())
                .renterId(p.getRenterId())
                .ownerId(p.getOwnerId())
                .type(p.getType())
                .status(p.getStatus())
                .currency(p.getCurrency())
                .monthlyRent(p.getMonthlyRent())
                .monthlyCharges(p.getMonthlyCharges())
                .depositAmount(p.getDepositAmount())
                .totalAmount(p.getTotalAmount())
                .costBreakdownSnapshot(p.getCostBreakdownSnapshot())
                .excludedChargesSnapshot(p.getExcludedChargesSnapshot())
                .expiresAt(p.getExpiresAt())
                .paidAt(p.getPaidAt())
                .contractDueAt(p.getContractDueAt())
                .provider(p.getProvider())
                .providerTransactionId(p.getProviderTransactionId())
                .refundedAt(p.getRefundedAt())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
