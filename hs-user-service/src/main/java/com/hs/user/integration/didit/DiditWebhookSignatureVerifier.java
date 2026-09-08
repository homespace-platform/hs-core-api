package com.hs.user.integration.didit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.config.didit.DiditProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Verifies Didit webhook authenticity.
 * Prefer {@code X-Signature-V2} (canonical JSON) with timestamp anti-replay window.
 */
@Component
@RequiredArgsConstructor
public class DiditWebhookSignatureVerifier {

    public static final long MAX_SKEW_SECONDS = 300L;

    private final DiditProperties properties;
    private final ObjectMapper objectMapper;

    public void verifyOrThrow(
            String rawBody,
            String signatureV2,
            String signatureSimple,
            String timestampHeader,
            String sessionId,
            String status,
            String webhookType
    ) {
        if (!properties.isWebhookConfigured()) {
            throw new AppException(UserErrorCode.KYC_NOT_CONFIGURED);
        }
        if (timestampHeader == null || timestampHeader.isBlank()) {
            throw new AppException(UserErrorCode.KYC_WEBHOOK_INVALID);
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException ex) {
            throw new AppException(UserErrorCode.KYC_WEBHOOK_INVALID);
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > MAX_SKEW_SECONDS) {
            throw new AppException(UserErrorCode.KYC_WEBHOOK_STALE);
        }

        String secret = properties.webhookSecret();
        boolean ok = false;

        if (signatureV2 != null && !signatureV2.isBlank() && rawBody != null) {
            ok = constantTimeEquals(signatureV2.trim(), hmacHex(secret, canonicalJson(rawBody)));
        }

        if (!ok && signatureSimple != null && !signatureSimple.isBlank()) {
            String simplePayload = timestampHeader.trim() + ":"
                    + nullToEmpty(sessionId) + ":"
                    + nullToEmpty(status) + ":"
                    + nullToEmpty(webhookType);
            ok = constantTimeEquals(signatureSimple.trim(), hmacHex(secret, simplePayload));
        }

        if (!ok) {
            throw new AppException(UserErrorCode.KYC_WEBHOOK_INVALID);
        }
    }

    String canonicalJson(String rawBody) {
        try {
            JsonNode tree = objectMapper.readTree(rawBody);
            JsonNode normalized = sortKeys(shortenFloats(tree));
            // Compact JSON, Unicode preserved (no escaping non-ASCII)
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ex) {
            throw new AppException(UserErrorCode.KYC_WEBHOOK_INVALID);
        }
    }

    private JsonNode shortenFloats(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                array.add(shortenFloats(child));
            }
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                object.set(entry.getKey(), shortenFloats(entry.getValue()));
            }
            return object;
        }
        if (node.isFloatingPointNumber()) {
            double value = node.asDouble();
            if (value == Math.rint(value) && !Double.isInfinite(value) && !Double.isNaN(value)) {
                return objectMapper.getNodeFactory().numberNode((long) value);
            }
        }
        return node;
    }

    private JsonNode sortKeys(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                array.add(sortKeys(child));
            }
            return array;
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                sorted.put(entry.getKey(), sortKeys(entry.getValue()));
            }
            ObjectNode object = objectMapper.createObjectNode();
            sorted.forEach(object::set);
            return object;
        }
        return node;
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new AppException(UserErrorCode.KYC_WEBHOOK_INVALID);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
