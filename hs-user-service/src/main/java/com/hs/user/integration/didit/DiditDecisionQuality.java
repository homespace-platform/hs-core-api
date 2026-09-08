package com.hs.user.integration.didit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * HomeSpace-side quality gates on top of Didit Approved.
 * Scores are 0–100 from Didit decision arrays.
 */
public final class DiditDecisionQuality {

    /** Face match similarity floor (Didit often approves near ~80). */
    public static final double MIN_FACE_MATCH_SCORE = 80.0;

    /** Liveness score floor. */
    public static final double MIN_LIVENESS_SCORE = 70.0;

    private DiditDecisionQuality() {
    }

    /**
     * @return human-readable rejection reason, or null if quality is acceptable / not present
     */
    public static String findRejectionReason(JsonNode payload) {
        JsonNode decision = payload == null ? null : payload.get("decision");
        if (decision == null || decision.isNull()) {
            return null;
        }

        String faceReason = checkScores(
                decision.get("face_matches"),
                MIN_FACE_MATCH_SCORE,
                "Độ khớp khuôn mặt quá thấp"
        );
        if (faceReason != null) {
            return faceReason;
        }

        return checkScores(
                decision.get("liveness_checks"),
                MIN_LIVENESS_SCORE,
                "Điểm liveness quá thấp"
        );
    }

    private static String checkScores(JsonNode array, double minScore, String label) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        double best = Double.NEGATIVE_INFINITY;
        boolean sawScore = false;
        for (JsonNode item : array) {
            if (item == null || item.isNull()) {
                continue;
            }
            JsonNode scoreNode = item.get("score");
            if (scoreNode == null || scoreNode.isNull() || !scoreNode.isNumber()) {
                continue;
            }
            sawScore = true;
            best = Math.max(best, scoreNode.asDouble());
        }
        if (!sawScore) {
            return null;
        }
        if (best < minScore) {
            return label + " (" + formatScore(best) + "% < " + formatScore(minScore) + "%)";
        }
        return null;
    }

    private static String formatScore(double value) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
