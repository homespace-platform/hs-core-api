package com.hs.user.service.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VnptKycDetailsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode result(String object) throws Exception {
        return mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":" + object + "}");
    }

    @Test void mapsVendorPersonalAndValidationFieldsWithoutImagesOrHashes() throws Exception {
        var ocr = result("{\"id\":\"001234567890\",\"name\":\"NGUYEN VAN A\",\"birth_day\":\"01/01/2000\",\"recent_location\":\"Hanoi\",\"gender\":\"Nam\",\"issue_date\":\"01/01/2020\",\"valid_date\":\"01/01/2030\",\"issue_place\":\"Noi cap\",\"type_id\":1,\"back_type_id\":1,\"id_probs\":\"[0.98,0.99]\",\"issue_date_prob\":0.97,\"msg_front\":\"OK\",\"msg_back\":\"OK\",\"expire_warning\":\"no\",\"match_front_back\":{\"match_id\":\"yes\",\"match_name\":\"yes\",\"match_bod\":\"yes\",\"match_sex\":\"yes\"},\"card_type\":\"CĂN CƯỚC\",\"checking_result_front\":{\"corner_cut_result\":\"0\",\"edited_result\":\"0\",\"recaptured_result\":\"0\",\"check_photocopied_result\":\"0\"},\"warning\":[],\"tampering\":{\"warning\":[]},\"corner_warning\":\"no\"}");
        var details = VnptKycDetails.from(ocr, result("{\"msg\":\"MATCH\",\"prob\":97.654}"),
                result("{\"liveness\":\"success\",\"is_eye_open\":\"yes\",\"blur_face\":\"no\"}"),
                result("{\"masked\":\"no\"}"), result("{\"fake_liveness\":false,\"face_swapping\":false}"),
                result("{\"fake_liveness\":false}"), mapper);
        assertEquals("001234567890", details.cccd());
        assertEquals("NGUYEN VAN A", details.fullName());
        assertEquals("Hanoi", details.permanentAddress());
        assertEquals(97.654, details.faceMatchPercent());
        assertTrue(details.documentValid());
        assertTrue(details.frontBackIdentical());
        assertTrue(details.idQualityGood());
        assertTrue(details.faceValid());
        assertTrue(details.noWarnings());
        assertFalse(mapper.writeValueAsString(details).contains("hash"));
    }

    @Test void missingVendorChecksRemainUnknown() throws Exception {
        var empty = result("{}");
        var details = VnptKycDetails.from(empty, empty, empty, empty, empty, empty, mapper);
        assertNull(details.idQualityGood());
        assertNull(details.frontBackIdentical());
        assertNull(details.eyesOpen());
        assertNull(details.faceMatchPercent());
    }
}
