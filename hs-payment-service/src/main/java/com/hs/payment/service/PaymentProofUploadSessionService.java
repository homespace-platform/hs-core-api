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
import com.hs.payment.repository.PaymentProofUploadSessionRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            "image/png",
            "image/webp",
            "application/pdf"
    );

    private final SecureRandom secureRandom = new SecureRandom();
    private final PaymentProofUploadSessionRepository uploadSessionRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final StorageService storageService;

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
        if (session.getStatus() == UploadSessionStatus.UPLOADED && session.getStorageId() != null) {
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
     */
    @Transactional
    public PaymentProofUploadSession handleMobileUpload(
            String rawToken,
            byte[] fileBytes,
            String originalFileName,
            String contentType,
            long fileSize
    ) {
        PaymentProofUploadSession session = findValidSessionByRawToken(rawToken);
        if (session == null) {
            throw new AppException(PaymentErrorCode.PROOF_SESSION_INVALID);
        }

        if (fileBytes == null || fileBytes.length == 0) {
            throw new AppException(PaymentErrorCode.PAYMENT_PROOF_REQUIRED);
        }

        if (fileSize > MAX_FILE_SIZE || fileBytes.length > MAX_FILE_SIZE) {
            throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID, "Dung lượng tệp không được vượt quá 15MB.");
        }

        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.trim().toLowerCase())) {
            throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID, "Chỉ chấp nhận ảnh (JPG, PNG, WebP) hoặc tệp PDF.");
        }

        session.setStatus(UploadSessionStatus.UPLOADING);
        uploadSessionRepository.save(session);

        // Upload trực tiếp vào Storage Service dạng PRIVATE, purpose PAYMENT_PROOF, referenceId = paymentRequestId
        StorageObjectResponse uploaded = storageService.uploadDirect(
                fileBytes,
                originalFileName != null ? originalFileName : "mobile_proof.jpg",
                contentType,
                StoragePurpose.PAYMENT_PROOF,
                "PAYMENT_REQUEST",
                session.getPaymentRequestId(),
                StorageVisibility.PRIVATE
        );

        session.setStorageId(uploaded.id());
        session.setOriginalFileName(originalFileName);
        session.setContentType(contentType);
        session.setFileSize((long) fileBytes.length);
        session.setStatus(UploadSessionStatus.UPLOADED);
        session.setUploadedAt(Instant.now());

        PaymentProofUploadSession saved = uploadSessionRepository.save(session);
        log.info("Mobile upload successful for session [{}] storageId [{}]", saved.getId(), uploaded.id());
        return saved;
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
