package com.hs.payment.service;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.CreateUploadSessionResponse;
import com.hs.payment.dto.UploadSessionEvidenceResponse;
import com.hs.payment.dto.UploadSessionStatusResponse;
import com.hs.payment.model.PaymentProofUploadSession;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.UploadSessionStatus;
import com.hs.payment.model.PaymentEvidence;
import com.hs.payment.model.PaymentEvent;
import com.hs.payment.model.constant.PaymentEventType;
import com.hs.payment.repository.PaymentEvidenceRepository;
import com.hs.payment.repository.PaymentEventRepository;
import com.hs.payment.repository.PaymentProofUploadSessionRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProofUploadSessionService {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final Duration SESSION_TTL = Duration.ofMinutes(10);
    private static final long MAX_FILE_SIZE = 15L * 1024 * 1024; // 15MB
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/pjpeg",
            "image/png",
            "image/webp",
            "application/pdf"
    );

    private final SecureRandom secureRandom = new SecureRandom();
    private final PaymentProofUploadSessionRepository uploadSessionRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentEvidenceRepository paymentEvidenceRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final StorageService storageService;

    @Value("${homespace.payment.confirmation-window-hours:24}")
    private long confirmationWindowHours = 24;

    /**
     * Tạo một upload session mới cho người thuê trên desktop.
     * Vô hiệu hóa bất kỳ active session nào trước đó của payment request này.
     */
    @Transactional
    public CreateUploadSessionResponse createSession(
            String paymentRequestId,
            String actorId,
            String baseUrl
    ) {
        PaymentRequest payment = paymentRequestRepository.findById(paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        if (!actorId.equals(payment.getPayerId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        if (payment.getStatus() != PaymentStatus.AWAITING_TRANSFER && payment.getStatus() != PaymentStatus.REJECTED) {
            throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS);
        }

        // Vô hiệu hóa bất kỳ session nào đang active
        List<PaymentProofUploadSession> existingActiveSessions = uploadSessionRepository
                .findByPaymentRequestIdAndTenantUserIdAndStatusIn(
                        paymentRequestId,
                        actorId,
                        List.of(UploadSessionStatus.CREATED, UploadSessionStatus.UPLOADING)
                );
        for (PaymentProofUploadSession old : existingActiveSessions) {
            old.setStatus(UploadSessionStatus.CANCELLED);
            uploadSessionRepository.save(old);
        }

        // Sinh 32 bytes ngẫu nhiên bằng SecureRandom, encode URL-safe không padding
        byte[] tokenBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        // Chỉ lưu SHA-256 hash trong database
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().plus(SESSION_TTL);

        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .paymentRequestId(payment.getId())
                .tenantUserId(actorId)
                .tokenHash(tokenHash)
                .status(UploadSessionStatus.CREATED)
                .expiresAt(expiresAt)
                .build();

        PaymentProofUploadSession saved = uploadSessionRepository.save(session);

        String uploadPath = "/u/payment-proof/" + rawToken;
        String uploadPageUrl = (baseUrl != null ? baseUrl : "") + uploadPath;

        log.info("Created proof upload session [{}] for paymentRequestId [{}] tenant [{}]",
                saved.getId(), payment.getId(), actorId);

        return CreateUploadSessionResponse.builder()
                .sessionId(saved.getId())
                .uploadPath(uploadPath)
                .uploadPageUrl(uploadPageUrl)
                .expiresAt(expiresAt)
                .status(saved.getStatus())
                .build();
    }

    /**
     * Desktop gọi định kỳ (polling) để kiểm tra trạng thái session.
     */
    @Transactional
    public UploadSessionStatusResponse getSessionStatus(
            String paymentRequestId,
            String sessionId,
            String actorId
    ) {
        PaymentProofUploadSession session = uploadSessionRepository.findByIdAndPaymentRequestId(sessionId, paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PROOF_SESSION_NOT_FOUND));

        if (!actorId.equals(session.getTenantUserId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        // Kiểm tra hết hạn
        if ((session.getStatus() == UploadSessionStatus.CREATED || session.getStatus() == UploadSessionStatus.UPLOADING)
                && Instant.now().isAfter(session.getExpiresAt())) {
            session.setStatus(UploadSessionStatus.EXPIRED);
            uploadSessionRepository.save(session);
        }

        UploadSessionEvidenceResponse evidenceResponse = null;
        if ((session.getStatus() == UploadSessionStatus.UPLOADED || session.getStatus() == UploadSessionStatus.CONSUMED)
                && session.getStorageId() != null) {
            String previewUrl = null;
            try {
                previewUrl = storageService.createViewUrl(session.getStorageId()).url();
            } catch (Exception e) {
                log.warn("Unable to generate preview URL for storageId [{}]: {}", session.getStorageId(), e.getMessage());
            }

            evidenceResponse = UploadSessionEvidenceResponse.builder()
                    .storageId(session.getStorageId())
                    .originalFileName(session.getOriginalFileName())
                    .contentType(session.getContentType())
                    .fileSize(session.getFileSize())
                    .previewUrl(previewUrl)
                    .build();
        }

        return UploadSessionStatusResponse.builder()
                .sessionId(session.getId())
                .status(session.getStatus())
                .expiresAt(session.getExpiresAt())
                .evidence(evidenceResponse)
                .build();
    }

    /**
     * Mobile mở link Thymeleaf qua GET /u/payment-proof/{rawToken}.
     */
    @Transactional
    public PaymentProofUploadSession findValidSessionByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }

        String tokenHash = hashToken(rawToken);
        PaymentProofUploadSession session = uploadSessionRepository.findByTokenHash(tokenHash).orElse(null);
        if (session == null) {
            return null;
        }

        // Idempotency: nếu đã CONSUMED và PaymentRequest đã TRANSFER_REPORTED/CONFIRMED, cho phép xem lại thành công
        if (session.getStatus() == UploadSessionStatus.CONSUMED) {
            PaymentRequest payment = paymentRequestRepository.findById(session.getPaymentRequestId()).orElse(null);
            if (payment != null && (payment.getStatus() == PaymentStatus.TRANSFER_REPORTED || payment.getStatus() == PaymentStatus.CONFIRMED)) {
                return session;
            }
        }

        if (Instant.now().isAfter(session.getExpiresAt())) {
            if (session.getStatus() == UploadSessionStatus.CREATED || session.getStatus() == UploadSessionStatus.UPLOADING) {
                session.setStatus(UploadSessionStatus.EXPIRED);
                uploadSessionRepository.save(session);
            }
            return null;
        }

        if (session.getStatus() != UploadSessionStatus.CREATED && session.getStatus() != UploadSessionStatus.UPLOADING) {
            return null;
        }

        return session;
    }

    /**
     * Mobile POST file chứng từ qua POST /u/payment-proof/{rawToken}.
     * Tự động lưu file, tạo PaymentEvidence, chuyển PaymentRequest sang TRANSFER_REPORTED,
     * chuyển upload session sang CONSUMED trong 1 transaction duy nhất.
     */
    @Transactional
    public PaymentProofUploadSession handleMobileUpload(
            String rawToken,
            byte[] fileBytes,
            String originalFileName,
            String contentType,
            long fileSize
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AppException(PaymentErrorCode.PROOF_SESSION_INVALID, "Phiên tải chứng từ không hợp lệ.");
        }

        String tokenHash = hashToken(rawToken);
        PaymentProofUploadSession session = uploadSessionRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PROOF_SESSION_INVALID, "Phiên tải chứng từ không tồn tại."));

        // Idempotency: Reload / Re-POST sau khi đã gửi thành công
        if (session.getStatus() == UploadSessionStatus.CONSUMED) {
            PaymentRequest existingPayment = paymentRequestRepository.findById(session.getPaymentRequestId()).orElse(null);
            if (existingPayment != null && (existingPayment.getStatus() == PaymentStatus.TRANSFER_REPORTED || existingPayment.getStatus() == PaymentStatus.CONFIRMED)) {
                log.info("Idempotent POST: Session [{}] is already CONSUMED and payment [{}] is in status [{}]",
                        session.getId(), existingPayment.getId(), existingPayment.getStatus());
                return session;
            }
        }

        if (Instant.now().isAfter(session.getExpiresAt())) {
            session.setStatus(UploadSessionStatus.EXPIRED);
            uploadSessionRepository.save(session);
            throw new AppException(PaymentErrorCode.PROOF_SESSION_EXPIRED, "Phiên tải chứng từ đã hết hạn.");
        }

        if (session.getStatus() != UploadSessionStatus.CREATED && session.getStatus() != UploadSessionStatus.UPLOADING) {
            throw new AppException(PaymentErrorCode.PROOF_SESSION_INVALID, "Phiên tải chứng từ không hợp lệ hoặc đã kết thúc.");
        }

        PaymentRequest payment = paymentRequestRepository.findByIdForUpdate(session.getPaymentRequestId())
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND, "Không tìm thấy yêu cầu thanh toán."));

        if (payment.getStatus() == PaymentStatus.CONFIRMED) {
            throw new AppException(PaymentErrorCode.PAYMENT_ALREADY_CONFIRMED, "Yêu cầu thanh toán đã được xác nhận.");
        }

        if (payment.getStatus() == PaymentStatus.TRANSFER_REPORTED) {
            // Idempotent: Payment đã được báo chuyển khoản
            session.setStatus(UploadSessionStatus.CONSUMED);
            return uploadSessionRepository.save(session);
        }

        if (payment.getStatus() != PaymentStatus.AWAITING_TRANSFER && payment.getStatus() != PaymentStatus.REJECTED) {
            throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS, "Trạng thái thanh toán không hợp lệ.");
        }

        if (fileBytes == null || fileBytes.length == 0) {
            throw new AppException(PaymentErrorCode.PAYMENT_PROOF_REQUIRED, "Vui lòng chọn ảnh hoặc tệp chứng từ chuyển khoản.");
        }

        if (fileSize > MAX_FILE_SIZE || fileBytes.length > MAX_FILE_SIZE) {
            throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID, "Dung lượng file không được vượt quá 15 MB.");
        }

        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.trim().toLowerCase())) {
            throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID, "Chỉ chấp nhận JPG, PNG, WebP hoặc PDF.");
        }

        session.setStatus(UploadSessionStatus.UPLOADING);
        uploadSessionRepository.save(session);

        StorageObjectResponse uploaded;
        try {
            uploaded = storageService.uploadDirect(
                    fileBytes,
                    originalFileName != null ? originalFileName : "mobile_proof.jpg",
                    contentType,
                    StoragePurpose.PAYMENT_PROOF,
                    "PAYMENT_REQUEST",
                    session.getPaymentRequestId(),
                    StorageVisibility.PRIVATE,
                    session.getTenantUserId()
            );
        } catch (Exception e) {
            // Revert session về CREATED nếu upload thất bại để người dùng có thể thử lại
            session.setStatus(UploadSessionStatus.CREATED);
            uploadSessionRepository.save(session);
            throw e;
        }

        Instant now = Instant.now();
        PaymentStatus oldStatus = payment.getStatus();

        // 1. Tạo PaymentEvidence
        PaymentEvidence evidence = PaymentEvidence.builder()
                .paymentRequestId(payment.getId())
                .uploadedBy(session.getTenantUserId())
                .storageObjectId(uploaded.id())
                .declaredTransferTime(now)
                .bankTransactionReference(null)
                .payerAccountLast4(null)
                .note(null)
                .build();
        paymentEvidenceRepository.save(evidence);

        // 2. Chuyển PaymentRequest sang TRANSFER_REPORTED
        payment.setStatus(PaymentStatus.TRANSFER_REPORTED);
        payment.setPayerReportedAt(now);
        payment.setConfirmationDueAt(now.plus(Duration.ofHours(confirmationWindowHours)));
        if (payment.getRejectedAt() != null || payment.getRejectedReason() != null) {
            payment.setRejectedAt(null);
            payment.setRejectedReason(null);
        }
        paymentRequestRepository.save(payment);

        // 3. Chuyển session sang CONSUMED
        session.setStorageId(uploaded.id());
        session.setOriginalFileName(originalFileName);
        session.setContentType(contentType);
        session.setFileSize((long) fileBytes.length);
        session.setStatus(UploadSessionStatus.CONSUMED);
        session.setUploadedAt(now);
        session.setConsumedAt(now);
        PaymentProofUploadSession savedSession = uploadSessionRepository.save(session);

        // 4. Tạo audit event
        PaymentEvent event = PaymentEvent.builder()
                .paymentRequestId(payment.getId())
                .eventType(PaymentEventType.TRANSFER_REPORTED)
                .fromStatus(oldStatus)
                .toStatus(PaymentStatus.TRANSFER_REPORTED)
                .actorId(session.getTenantUserId())
                .actorRole("TENANT")
                .reason("Người thuê đã gửi chứng từ chuyển khoản.")
                .createdAt(now)
                .build();
        paymentEventRepository.save(event);

        log.info("Mobile upload & transfer report complete: session [{}] payment [{}] storageId [{}]",
                savedSession.getId(), payment.getId(), uploaded.id());

        return savedSession;
    }

    /**
     * Tiêu thụ session khi người thuê bấm submit report-transfer trên desktop (Transaction-safe).
     */
    @Transactional
    public String consumeSession(String sessionId, String paymentRequestId, String tenantUserId) {
        PaymentProofUploadSession session = uploadSessionRepository.findByIdAndPaymentRequestId(sessionId, paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PROOF_SESSION_NOT_FOUND));

        if (!tenantUserId.equals(session.getTenantUserId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        // Idempotent: nếu đã CONSUMED thì trả về storageId
        if (session.getStatus() == UploadSessionStatus.CONSUMED && session.getStorageId() != null) {
            return session.getStorageId();
        }

        if (session.getStatus() != UploadSessionStatus.UPLOADED) {
            throw new AppException(PaymentErrorCode.PROOF_SESSION_INVALID);
        }

        if (Instant.now().isAfter(session.getExpiresAt())) {
            session.setStatus(UploadSessionStatus.EXPIRED);
            uploadSessionRepository.save(session);
            throw new AppException(PaymentErrorCode.PROOF_SESSION_EXPIRED);
        }

        if (session.getStorageId() == null || session.getStorageId().isBlank()) {
            throw new AppException(PaymentErrorCode.PAYMENT_PROOF_REQUIRED);
        }

        session.setStatus(UploadSessionStatus.CONSUMED);
        session.setConsumedAt(Instant.now());
        uploadSessionRepository.save(session);

        log.info("Consumed proof upload session [{}] for paymentRequestId [{}] storageId [{}]",
                session.getId(), paymentRequestId, session.getStorageId());

        return session.getStorageId();
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
