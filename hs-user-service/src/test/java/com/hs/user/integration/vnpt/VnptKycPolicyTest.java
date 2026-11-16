package com.hs.user.integration.vnpt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VnptKycPolicyTest {
    ObjectMapper mapper=new ObjectMapper();
    @Test void technicalSuccessDoesNotMeanIdentityPassed() throws Exception {
        var noMatch=mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":{\"msg\":\"NOMATCH\"}}");
        assertTrue(VnptKycPolicy.technicalSuccess(noMatch));assertFalse(VnptKycPolicy.passed("compare",noMatch));
        assertFalse(VnptKycPolicy.passed("face",mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":{\"liveness\":\"failure\"}}")));
    }
    @Test void ocrNeedsBothSidesValidExpiryAndTwelveDigitString() throws Exception {
        var valid=mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":{\"id\":\"001234567890\",\"msg_front\":\"OK\",\"msg_back\":\"OK\",\"expire_warning\":\"no\",\"match_front_back\":{\"match_id\":\"yes\",\"match_name\":\"yes\",\"match_bod\":\"yes\",\"match_sex\":\"yes\"},\"card_type\":\"CĂN CƯỚC\",\"checking_result_front\":{\"corner_cut_result\":\"0\",\"edited_result\":\"0\",\"recaptured_result\":\"0\",\"check_photocopied_result\":\"0\"}}}");
        assertTrue(VnptKycPolicy.passed("ocr",valid));
        for(String key:new String[]{"msg_front","msg_back","expire_warning","id"}) {
            var copy=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)copy.path("object")).remove(key);assertFalse(VnptKycPolicy.passed("ocr",copy));
        }
        var warned=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)warned.path("object")).putArray("warning").add("tampering");assertFalse(VnptKycPolicy.passed("ocr",warned));
        var numeric=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)numeric.path("object")).put("id",123456789012L);assertFalse(VnptKycPolicy.passed("ocr",numeric));
    }
    @Test void missingEvidenceAndUnknownSchemaFailClosed() throws Exception {
        for(String kind:new String[]{"ocr","card","face","compare","mask"}) {
            assertFalse(VnptKycPolicy.passed(kind,mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":{}}")));
            assertFalse(VnptKycPolicy.passed(kind,mapper.readTree("{\"object\":{\"msg\":\"MATCH\",\"liveness\":\"success\",\"masked\":\"no\"}}")));
        }
    }
}
