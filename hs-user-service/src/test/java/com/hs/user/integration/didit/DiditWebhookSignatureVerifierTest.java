package com.hs.user.integration.didit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.config.didit.DiditProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiditWebhookSignatureVerifierTest {

    private static final String SECRET = "test-webhook-secret";

    private DiditWebhookSignatureVerifier verifier;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        DiditProperties properties = new DiditProperties(
                new DiditProperties.Api("https://verification.didit.me", "test-key"),
                "workflow-id",
                SECRET
        );
        verifier = new DiditWebhookSignatureVerifier(properties, objectMapper);
    }

    @Test
    void acceptsValidSignatureV2() throws Exception {
        String body = "{\"event_id\":\"e1\",\"session_id\":\"s1\",\"status\":\"Approved\",\"webhook_type\":\"status.updated\",\"vendor_data\":\"u1\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String sig = hmac(SECRET, verifier.canonicalJson(body));

        verifier.verifyOrThrow(body, sig, null, ts, "s1", "Approved", "status.updated");
    }

    @Test
    void rejectsInvalidSignature() {
        String body = "{\"event_id\":\"e1\",\"session_id\":\"s1\",\"status\":\"Approved\",\"webhook_type\":\"status.updated\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        AppException ex = assertThrows(AppException.class, () ->
                verifier.verifyOrThrow(body, "deadbeef", null, ts, "s1", "Approved", "status.updated"));
        assertEquals(UserErrorCode.KYC_WEBHOOK_INVALID.getCode(), ex.getCode());
    }

    @Test
    void rejectsMissingSignature() {
        String body = "{\"event_id\":\"e1\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        AppException ex = assertThrows(AppException.class, () ->
                verifier.verifyOrThrow(body, null, null, ts, "s1", "Approved", "status.updated"));
        assertEquals(UserErrorCode.KYC_WEBHOOK_INVALID.getCode(), ex.getCode());
    }

    @Test
    void rejectsStaleTimestamp() throws Exception {
        String body = "{\"event_id\":\"e1\",\"session_id\":\"s1\",\"status\":\"Approved\",\"webhook_type\":\"status.updated\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond() - 600);
        String sig = hmac(SECRET, verifier.canonicalJson(body));

        AppException ex = assertThrows(AppException.class, () ->
                verifier.verifyOrThrow(body, sig, null, ts, "s1", "Approved", "status.updated"));
        assertEquals(UserErrorCode.KYC_WEBHOOK_STALE.getCode(), ex.getCode());
    }

    @Test
    void acceptsSignatureSimpleFallback() throws Exception {
        String body = "{\"event_id\":\"e1\",\"session_id\":\"s1\",\"status\":\"Declined\",\"webhook_type\":\"status.updated\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String simple = hmac(SECRET, ts + ":s1:Declined:status.updated");

        verifier.verifyOrThrow(body, "bad-v2", simple, ts, "s1", "Declined", "status.updated");
    }

    @Test
    void mapsStatuses() {
        assertEquals(com.hs.user.model.constant.KycStatus.VERIFIED, DiditStatusMapper.toHomeSpaceStatus("Approved"));
        assertEquals(com.hs.user.model.constant.KycStatus.REJECTED, DiditStatusMapper.toHomeSpaceStatus("Declined"));
        assertEquals(com.hs.user.model.constant.KycStatus.REVIEW_REQUIRED, DiditStatusMapper.toHomeSpaceStatus("In Review"));
        assertEquals(com.hs.user.model.constant.KycStatus.EXPIRED, DiditStatusMapper.toHomeSpaceStatus("Abandoned"));
        assertEquals(com.hs.user.model.constant.KycStatus.PENDING, DiditStatusMapper.toHomeSpaceStatus("In Progress"));
    }

    private static String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
