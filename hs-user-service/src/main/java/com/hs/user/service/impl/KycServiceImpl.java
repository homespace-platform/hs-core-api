package com.hs.user.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.response.KycSessionResponse;
import com.hs.user.dto.response.KycStatusResponse;
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

    private final UserRepository userRepository;
    private final KycVerificationRepository kycVerificationRepository;
    private final KycWebhookEventRepository kycWebhookEventRepository;
    private final DiditSessionClient diditSessionClient;
    private final DiditWebhookSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public KycStatusResponse getCurrentStatus(String userId) {
        requireUser(userId);
        Optional<KycVerification> latest =
                kycVerificationRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(
                        userId, KycProvider.DIDIT);

        if (latest.isEmpty()) {
            return new KycStatusResponse(KycStatus.NOT_VERIFIED, null, KycProvider.DIDIT, null, null);
        }

        KycVerification v = latest.get();
        return new KycStatusResponse(
                v.getStatus(),
                v.getVerifiedAt(),
                v.getProvider(),
                v.getProviderSessionId(),
                v.getStatus() == KycStatus.VERIFIED ? null : v.getSessionUrl()
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
            if (eventId != null) {
                kycWebhookEventRepository.save(KycWebhookEvent.builder()
                        .eventId(eventId)
                        .sessionId(sessionId)
                        .webhookType(webhookType)
                        .processedAt(Instant.now())
                        .build());
            }
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

        KycStatus mapped = DiditStatusMapper.toHomeSpaceStatus(status);
        verification.setProviderStatus(status);
        verification.setStatus(mapped);
        verification.setLastEventId(eventId);
        if (workflowId != null && !workflowId.isBlank()) {
            verification.setWorkflowId(workflowId);
        }
        if (mapped == KycStatus.VERIFIED) {
            verification.setVerifiedAt(Instant.now());
            verification.setRejectionReason(null);
            applyCitizenIdFromDecision(verification.getUserId(), payload);
        } else if (mapped == KycStatus.REJECTED) {
            verification.setRejectionReason(extractRejectionHint(payload));
        }

        kycVerificationRepository.save(verification);

        if (eventId != null && !eventId.isBlank()) {
            kycWebhookEventRepository.save(KycWebhookEvent.builder()
                    .eventId(eventId)
                    .sessionId(sessionId)
                    .webhookType(webhookType)
                    .processedAt(Instant.now())
                    .build());
        }

        log.info("Applied Didit webhook sessionId={} providerStatus={} homespaceStatus={}",
                sessionId, status, mapped);
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank() || !userRepository.existsById(userId)) {
            throw new AppException(UserErrorCode.USER_NOT_EXISTED);
        }
    }

    private void applyCitizenIdFromDecision(String userId, JsonNode payload) {
        String citizenId = extractPersonalNumber(payload);
        if (citizenId == null || citizenId.isBlank()) {
            log.info("Didit Approved without personal_number for user={}", userId);
            return;
        }

        userRepository.findById(userId).ifPresent(user -> {
            String normalized = citizenId.trim();
            if (normalized.equals(user.getCccd())) {
                return;
            }
            user.setCccd(normalized);
            userRepository.save(user);
            log.info("Synced CCCD (personal_number) from Didit KYC for user={}", userId);
        });
    }

    /**
     * Vietnamese CCCD is Didit {@code personal_number} (12 digits), not {@code document_number}
     * (often the shorter ID card serial / old CMND).
     */
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

    private static String extractRejectionHint(JsonNode payload) {
        JsonNode decision = payload.get("decision");
        if (decision == null || decision.isNull()) {
            return null;
        }
        // Prefer first warning short_description without storing PII document numbers.
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
