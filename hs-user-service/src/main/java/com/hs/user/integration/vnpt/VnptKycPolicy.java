package com.hs.user.integration.vnpt;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Only vendor responses received by the server are evaluated here. */
public final class VnptKycPolicy {
    private VnptKycPolicy() {}
    public static boolean technicalSuccess(JsonNode result) {
        return result != null && "IDG-00000000".equals(result.path("message").asText()) && result.path("object").isObject();
    }
    public static boolean passed(String kind, JsonNode result) {
        if (!technicalSuccess(result)) return false;
        JsonNode o=result.path("object");
        return switch(kind) {
            case "card", "face" -> "success".equalsIgnoreCase(o.path("liveness").asText())
                && noFraudFlag(o.path("fake_liveness")) && noFraudFlag(o.path("fake_print_photo")) && noFraudFlag(o.path("face_swapping"));
            case "compare" -> "MATCH".equals(o.path("msg").asText());
            case "mask" -> "no".equalsIgnoreCase(o.path("masked").asText());
            case "ocr" -> o.path("id").isTextual() && o.path("id").asText().matches("[0-9]{12}")
                && Set.of("CĂN CƯỚC", "CĂN CƯỚC CÔNG DÂN").contains(o.path("card_type").asText().trim().toUpperCase(Locale.ROOT))
                && "OK".equals(o.has("msg_front") ? o.path("msg_front").asText() : o.path("msg").asText()) && "OK".equals(o.path("msg_back").asText())
                && "no".equalsIgnoreCase(o.path("expire_warning").asText())
                && noWarning(o.path("warning")) && noWarning(o.path("tampering").path("warning"))
                && noWarning(o.path("id_fake_warning"))
                && cleanDocument(o.path("checking_result_front"))
                && matchingSides(o.path("match_front_back")) && noWarning(o.path("corner_warning"));
            default -> false;
        };
    }
    private static boolean matchingSides(JsonNode node) {
        return node.isObject() && List.of("match_id", "match_name", "match_bod", "match_sex").stream()
                .allMatch(key -> "yes".equalsIgnoreCase(node.path(key).asText()));
    }
    private static boolean cleanDocument(JsonNode node) {
        if (!node.isObject()) return false;
        return List.of("corner_cut_result", "edited_result", "recaptured_result", "check_photocopied_result").stream()
                .allMatch(key -> "0".equals(node.path(key).asText()));
    }
    private static boolean noFraudFlag(JsonNode node) {
        return node.isMissingNode() || node.isNull() || (node.isBoolean() && !node.asBoolean())
                || (node.isNumber() && node.asDouble() == 0) || (node.isTextual() && Set.of("no", "0", "false").contains(node.asText().toLowerCase(Locale.ROOT)));
    }
    private static boolean noWarning(JsonNode node) {
        return node.isMissingNode() || node.isNull() || (node.isArray() && node.isEmpty())
            || (node.isTextual() && (node.asText().isBlank() || "no".equalsIgnoreCase(node.asText())));
    }
}
