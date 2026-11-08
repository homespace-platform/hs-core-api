package com.hs.contract.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.config.SmartCaProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmartCaStatusResultTest {
    private final SmartCaClient client = new SmartCaClient(new SmartCaProperties(), new ObjectMapper());

    @Test void cancellationAliasesAreTerminalEvenIfThePayloadAlsoContainsASignature() {
        for (String status : new String[] {"CANCELLED", "canceled", " rejected "}) {
            var result = client.parseStatusResult("""
                    {"status_code":200,"data":{"status":"%s","signature_value":"test-signature"}}
                    """.formatted(status), "transaction", "doc");
            assertEquals("REJECTED", result.status());
            assertTrue(result.isRejectedOrFailed()); assertFalse(result.isSigned());
        }
    }

    @Test void terminalStatusCanBeReturnedAtTheEnvelopeLevelOrAsAnExactMessage() {
        for (String response : new String[] {
                "{\"status_code\":200,\"status_name\":\"CANCELLED\",\"data\":null}",
                "{\"status\":\"CANCELED\"}",
                "{\"status_code\":200,\"message\":\"REJECTED\",\"data\":null}"}) {
            var result = client.parseStatusResult(response, "transaction", "doc");
            assertEquals("REJECTED", result.status()); assertTrue(result.isRejectedOrFailed());
        }
    }

    @Test void providerOutageOrAuthenticationErrorIsNotAUserRejection() {
        for (int code : new int[] {401, 403, 404, 500}) {
            assertThrows(AppException.class, () -> client.parseStatusResult(
                    "{\"status_code\":" + code + ",\"message\":\"CANCELLED\"}", "transaction", "doc"));
        }
    }

    @Test void standardProviderSuccessStillRequiresTheMatchingDocumentSignature() {
        var signed = client.parseStatusResult("""
                {"status_code":200,"message":"SUCCESS","data":{"signatures":[
                {"doc_id":"another-doc","signature_value":"wrong-document"},
                {"doc_id":"doc","signature_value":"correct-document"}]}}
                """, "transaction", "doc");
        assertTrue(signed.isSigned()); assertEquals("correct-document", signed.signatureValue());
        var waiting = client.parseStatusResult("""
                {"status_code":200,"message":"sig_wait_for_user_confirm","data":{"signatures":[]}}
                """, "transaction", "doc");
        assertEquals("PENDING", waiting.status()); assertFalse(waiting.isSigned());
        assertFalse(waiting.isRejectedOrFailed());
    }

    @Test void expiredAndFailedResultsRemainDistinct() {
        for (String status : new String[] {"EXPIRED", "FAILED"}) {
            var result = client.parseStatusResult("{\"status_code\":200,\"data\":{\"status\":\"" + status + "\"}}", "transaction", "doc");
            assertEquals(status, result.status()); assertTrue(result.isRejectedOrFailed());
        }
    }
}
