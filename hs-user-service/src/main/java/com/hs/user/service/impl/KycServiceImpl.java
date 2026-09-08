package com.hs.user.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.response.KycSessionResponse;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.integration.didit.DiditDecisionQuality;
import com.hs.user.integration.didit.DiditSessionClient;
import com.hs.user.integration.didit.DiditStatusMapper;
import com.hs.user.integration.didit.DiditWebhookSignatureVerifier;
import com.hs.user.model.KycVerification;
import com.hs.user.model.KycWebhookEvent;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.KycWebhookEventRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.KycService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KycServiceImpl implements KycService {

    private static final Set<KycStatus> REUSABLE = EnumSet.of(
            KycStatus.PENDING,
            KycStatus.REVIEW_REQUIRED
    );

    private static final String REASON_DUPLICATE_CCCD =
            "CCCD này đã được xác minh trên tài khoản khác. Vui lòng dùng CCCD của bạn hoặc liên hệ hỗ trợ.";
    private static final String REASON_MISSING_CCCD =
            "Không đọc được số CCCD (personal number) từ giấy tờ. Vui lòng thử lại.";
    private static final String REASON_USER_CANCELLED =
            "Bạn đã hủy phiên xác minh. Nhấn Xác minh lại khi sẵn sàng.";

    private final UserRepository userRepository;
    private final KycVerificationRepository kycVerificationRepository;
    private final KycWebhookEventRepository kycWebhookEventRepository;
    private final DiditSessionClient diditSessionClient;
    private final DiditWebhookSignatureVerifier signatureVerifier;
    private final CccdClaimService cccdClaimService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public KycStatusResponse getCurrentStatus(String userId) {
        requireUser(userId);
        Optional<KycVerification> latest =
                kycVerificationRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(
                        userId, KycProvider.DIDIT);

        if (latest.isEmpty()) {
            return new KycStatusResponse(
                    KycStatus.NOT_VERIFIED, null, KycProvider.DIDIT, null, null, null);
        }

        KycVerification v = latest.get();
        boolean terminalFail = v.getStatus() == KycStatus.REJECTED || v.getStatus() == KycStatus.EXPIRED;
        return new KycStatusResponse(
                v.getStatus(),
                v.getVerifiedAt(),
                v.getProvider(),
                v.getProviderSessionId(),
                (v.getStatus() == KycStatus.VERIFIED || terminalFail) ? null : v.getSessionUrl(),
                v.getRejectionReason()
        );
    }

    @Override
    @Transactional
    public KycSessionResponse createOrReuseSession(String userId) {
        requireUser(userId);

        if (kycVerificationRepository.existsByUserIdAndProviderAndStatus(
                userId, KycProvider.DIDIT, KycStatus.VERIFIED)) {
            throw new AppException(UserErrorCode.KYC_ALREADY_VERIFIED);
        }

        List<KycVerification> active = kycVerificationRepository
                .findByUserIdAndProviderAndStatusInOrderByCreatedAtDesc(
                        userId, KycProvider.DIDIT, REUSABLE);

        if (!active.isEmpty()) {
            KycVerification existing = active.get(0);
            if (existing.getSessionUrl() != null && !existing.getSessionUrl().isBlank()) {
                return new KycSessionResponse(
                        existing.getProviderSessionId(),
                        existing.getSessionUrl(),
                        existing.getStatus()
                );
            }
        }

        DiditSessionClient.DiditSessionCreated created = diditSessionClient.createSession(userId);
        KycVerification saved = kycVerificationRepository.save(
                KycVerification.createPending(
                        userId,
                        created.sessionId(),
                        created.workflowId(),
                        created.url(),
                        created.status()
                )
        );

        log.info("Created Didit KYC session for user={} sessionId={}", userId, saved.getProviderSessionId());
        return new KycSessionResponse(saved.getProviderSessionId(), saved.getSessionUrl(), saved.getStatus());
    }

    @Override
    @Transactional
    public KycStatusResponse cancelPendingSession(String userId) {
        requireUser(userId);

        if (kycVerificationRepository.existsByUserIdAndProviderAndStatus(
                userId, KycProvider.DIDIT, KycStatus.VERIFIED)) {
            throw new AppException(UserErrorCode.KYC_ALREADY_VERIFIED);
        }

        List<KycVerification> active = kycVerificationRepository
                .findByUserIdAndProviderAndStatusInOrderByCreatedAtDesc(
                        userId, KycProvider.DIDIT, REUSABLE);

        if (active.isEmpty()) {
            throw new AppException(UserErrorCode.KYC_CANCEL_NOT_ALLOWED);
        }

        for (KycVerification verification : active) {
            verification.setStatus(KycStatus.EXPIRED);
            verification.setSessionUrl(null);
            verification.setVerifiedAt(null);
            verification.setRejectionReason(REASON_USER_CANCELLED);
            kycVerificationRepository.save(verification);
        }

        log.info("Cancelled {} pending KYC session(s) for user={}", active.size(), userId);
        return getCurrentStatus(userId);
    }

    @Override
    @Transactional
    public void handleDiditWebhook(
            String rawBody,
            String signatureV2,
            String signatureSimple,
            String timestampHeader
    ) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            throw new AppException(UserErrorCode.KYC_WEBHOOK_INVALID);
        }

        String eventId = text(payload, "event_id");
        String sessionId = text(payload, "session_id");
        String status = text(payload, "status");
        String webhookType = text(payload, "webhook_type");
        String vendorData = text(payload, "vendor_data");
        String workflowId = text(payload, "workflow_id");

        signatureVerifier.verifyOrThrow(
                rawBody, signatureV2, signatureSimple, timestampHeader, sessionId, status, webhookType);

        if (eventId != null && kycWebhookEventRepository.existsById(eventId)) {
            log.info("Ignoring duplicate Didit webhook eventId={}", eventId);
            return;
        }

        if (!"status.updated".equals(webhookType)) {
            markEventProcessed(eventId, sessionId, webhookType);
            return;
        }

        if (sessionId == null || sessionId.isBlank()) {
            throw new AppException(UserErrorCode.KYC_WEBHOOK_INVALID);
        }

        KycVerification verification = kycVerificationRepository
                .findByProviderAndProviderSessionId(KycProvider.DIDIT, sessionId)
                .orElseGet(() -> {
                    if (vendorData == null || vendorData.isBlank()) {
                        throw new AppException(UserErrorCode.KYC_SESSION_NOT_FOUND);
                    }
                    requireUser(vendorData);
                    return KycVerification.createPending(
                            vendorData, sessionId, workflowId, null, status);
                });

        if (vendorData != null
                && !vendorData.isBlank()
                && !vendorData.equals(verification.getUserId())) {
            log.warn("Didit webhook vendor_data mismatch sessionId={}", sessionId);
            throw new AppException(UserErrorCode.KYC_WEBHOOK_INVALID);
        }

        // Already terminal on our side — ack event, do not flip VERIFIED → something else lightly
        if (verification.getStatus() == KycStatus.VERIFIED
                && verification.getVerifiedAt() != null
                && !"Approved".equalsIgnoreCase(status)) {
            markEventProcessed(eventId, sessionId, webhookType);
            log.info("Ignoring non-Approved webhook for already VERIFIED sessionId={}", sessionId);
            return;
        }

        KycStatus mapped = DiditStatusMapper.toHomeSpaceStatus(status);
        verification.setProviderStatus(status);
        verification.setLastEventId(eventId);
        if (workflowId != null && !workflowId.isBlank()) {
            verification.setWorkflowId(workflowId);
        }

        if (mapped == KycStatus.VERIFIED) {
            applyApprovedDecision(verification, payload);
        } else if (mapped == KycStatus.REJECTED) {
            reject(verification, firstNonBlank(extractRejectionHint(payload), "Didit từ chối xác minh"));
        } else if (mapped == KycStatus.EXPIRED) {
            verification.setStatus(KycStatus.EXPIRED);
            verification.setVerifiedAt(null);
            verification.setSessionUrl(null);
            verification.setRejectionReason(null);
        } else {
            verification.setStatus(mapped);
        }

        kycVerificationRepository.save(verification);
        markEventProcessed(eventId, sessionId, webhookType);

        log.info("Applied Didit webhook sessionId={} providerStatus={} homespaceStatus={}",
                sessionId, status, verification.getStatus());
    }

    /**
     * Didit Approved → HomeSpace VERIFIED only if quality + unique CCCD pass.
     * Failures become REJECTED and still ack webhook (2xx) so Didit does not retry forever.
     */
    private void applyApprovedDecision(KycVerification verification, JsonNode payload) {
        String qualityReason = DiditDecisionQuality.findRejectionReason(payload);
        if (qualityReason != null) {
            reject(verification, qualityReason);
            return;
        }

        String citizenId = extractPersonalNumber(payload);
        if (citizenId == null || citizenId.isBlank()) {
            reject(verification, REASON_MISSING_CCCD);
            return;
        }

        String normalized = citizenId.trim();
        String userId = verification.getUserId();

        if (!cccdClaimService.tryClaim(userId, normalized)) {
            reject(verification, REASON_DUPLICATE_CCCD);
            return;
        }

        verification.setStatus(KycStatus.VERIFIED);
        verification.setVerifiedAt(Instant.now());
        verification.setRejectionReason(null);
        log.info("Synced CCCD (personal_number) from Didit KYC for user={}", userId);
    }

    private static void reject(KycVerification verification, String reason) {
        verification.setStatus(KycStatus.REJECTED);
        verification.setVerifiedAt(null);
        verification.setSessionUrl(null);
        verification.setRejectionReason(reason);
    }

    private void markEventProcessed(String eventId, String sessionId, String webhookType) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        if (kycWebhookEventRepository.existsById(eventId)) {
            return;
        }
        kycWebhookEventRepository.save(KycWebhookEvent.builder()
                .eventId(eventId)
                .sessionId(sessionId)
                .webhookType(webhookType)
                .processedAt(Instant.now())
                .build());
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank() || !userRepository.existsById(userId)) {
            throw new AppException(UserErrorCode.USER_NOT_EXISTED);
        }
    }

    private static String extractPersonalNumber(JsonNode payload) {
        JsonNode decision = payload.get("decision");
        if (decision == null || decision.isNull()) {
            return null;
        }
        JsonNode idVerifications = decision.get("id_verifications");
        if (idVerifications == null || !idVerifications.isArray()) {
            return null;
        }
        for (JsonNode item : idVerifications) {
            if (item == null) {
                continue;
            }
            if (item.hasNonNull("personal_number")) {
                String value = item.get("personal_number").asText();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private static String extractRejectionHint(JsonNode payload) {
        JsonNode decision = payload.get("decision");
        if (decision == null || decision.isNull()) {
            return null;
        }
        for (String arrayName : List.of("id_verifications", "face_matches", "liveness_checks")) {
            JsonNode arr = decision.get(arrayName);
            if (arr == null || !arr.isArray()) continue;
            for (JsonNode item : arr) {
                JsonNode warnings = item.get("warnings");
                if (warnings == null || !warnings.isArray() || warnings.isEmpty()) continue;
                JsonNode first = warnings.get(0);
                if (first != null && first.hasNonNull("short_description")) {
                    return first.get("short_description").asText();
                }
            }
        }
        return null;
    }
}
