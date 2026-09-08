package com.hs.user.integration.didit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DiditDecisionQualityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsScoresAtOrAboveThreshold() throws Exception {
        String json = """
                {"decision":{"face_matches":[{"score":80.82}],"liveness_checks":[{"score":95.16}]}}
                """;
        assertNull(DiditDecisionQuality.findRejectionReason(mapper.readTree(json)));
    }

    @Test
    void rejectsLowFaceMatch() throws Exception {
        String json = """
                {"decision":{"face_matches":[{"score":61.0}],"liveness_checks":[{"score":95.0}]}}
                """;
        String reason = DiditDecisionQuality.findRejectionReason(mapper.readTree(json));
        assertTrue(reason != null && reason.contains("khuôn mặt"));
    }

    @Test
    void rejectsLowLiveness() throws Exception {
        String json = """
                {"decision":{"face_matches":[{"score":90.0}],"liveness_checks":[{"score":40.0}]}}
                """;
        String reason = DiditDecisionQuality.findRejectionReason(mapper.readTree(json));
        assertTrue(reason != null && reason.toLowerCase().contains("liveness"));
    }
}
