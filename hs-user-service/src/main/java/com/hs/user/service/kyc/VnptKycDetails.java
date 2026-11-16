package com.hs.user.service.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.user.dto.response.KycVerifiedDetailsResponse;
import com.hs.user.integration.vnpt.VnptKycPolicy;
import java.util.List;
import java.util.Objects;

final class VnptKycDetails {
    private VnptKycDetails() {}

    static KycVerifiedDetailsResponse from(JsonNode ocr, JsonNode compare, JsonNode face,
                                           JsonNode mask, JsonNode front, JsonNode back, ObjectMapper mapper) {
        JsonNode o = ocr.path("object"), c = compare.path("object"), f = face.path("object");
        JsonNode m = mask.path("object"), a = front.path("object"), b = back.path("object");
        return new KycVerifiedDetailsResponse(
                value(o, "id"), value(o, "name"), value(o, "birth_day"), value(o, "recent_location"),
                value(o, "gender"), value(o, "issue_date"), value(o, "valid_date"), value(o, "issue_place"),
                number(c.path("prob")),
                VnptKycPolicy.passed("ocr", ocr), equal(o, "type_id", "back_type_id"),
                noWarning(o.path("warning"), o.path("corner_warning")), quality(o.path("id_probs"), mapper),
                quality(o.path("issue_date_prob"), mapper), yes(o, "expire_warning", "no"),
                VnptKycPolicy.passed("card", front) && VnptKycPolicy.passed("card", back),
                noWarning(o.path("warning"), o.path("tampering").path("warning"), o.path("id_fake_warning")),
                noFraud(a.path("face_swapping")), yes(f, "is_eye_open", "yes"),
                yes(f, "blur_face", "no"), VnptKycPolicy.passed("face", face),
                yes(m, "masked", "no"));
    }

    private static String value(JsonNode object, String key) {
        JsonNode node = object.path(key);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static Boolean equal(JsonNode object, String first, String second) {
        String a = scalar(object.path(first)), b = scalar(object.path(second));
        return a == null || b == null ? null : Objects.equals(a, b);
    }

    private static String scalar(JsonNode node) {
        return (node.isTextual() || node.isNumber()) && !node.asText().isBlank() ? node.asText() : null;
    }

    private static Boolean yes(JsonNode object, String key, String expected) {
        String actual = value(object, key);
        return actual == null ? null : expected.equalsIgnoreCase(actual);
    }

    private static Double number(JsonNode node) {
        try {
            double value = Double.parseDouble(node.asText());
            return Double.isFinite(value) && value >= 0 && value <= 100 ? value : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static Boolean quality(JsonNode node, ObjectMapper mapper) {
        if (node.isMissingNode() || node.isNull()) return null;
        try {
            JsonNode values = node.isTextual() && node.asText().startsWith("[") ? mapper.readTree(node.asText()) : node;
            if (values.isArray()) {
                if (values.isEmpty()) return null;
                for (JsonNode value : values) if (number(value) == null || value.asDouble() < .93) return false;
                return true;
            }
            Double value = number(values);
            return value == null ? null : value >= .93;
        } catch (Exception ignored) { return null; }
    }

    private static Boolean noFraud(JsonNode... nodes) {
        if (List.of(nodes).stream().allMatch(JsonNode::isMissingNode)) return null;
        for (JsonNode node : nodes) if (!node.isMissingNode() && !node.isNull()
                && !"no".equalsIgnoreCase(node.asText()) && !"false".equalsIgnoreCase(node.asText())
                && !"0".equals(node.asText())) return false;
        return true;
    }

    private static Boolean noWarning(JsonNode... nodes) {
        if (List.of(nodes).stream().allMatch(JsonNode::isMissingNode)) return null;
        for (JsonNode node : nodes) if (!node.isMissingNode() && !node.isNull()) {
            if (node.isArray()) { if (!node.isEmpty()) return false; }
            else if (!node.asText().isBlank() && !"no".equalsIgnoreCase(node.asText())) return false;
        }
        return true;
    }
}
