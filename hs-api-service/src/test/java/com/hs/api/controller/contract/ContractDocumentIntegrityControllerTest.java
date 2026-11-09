package com.hs.api.controller.contract;

import com.hs.api.advice.handler.GlobalException;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.blockchain.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContractDocumentIntegrityControllerTest {
    private final ContractDocumentIntegrityService service = mock(ContractDocumentIntegrityService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ContractDocumentIntegrityController(service))
            .setControllerAdvice(new GlobalException()).build();
    private final DocumentIntegrityResponse result = new DocumentIntegrityResponse("pdf", "MATCH", "hash", "hash", Instant.now(), "Khớp");

    @Test void integrityCheckUsesBothAliasesWithoutAcceptingClientHash() throws Exception {
        when(service.check("pdf")).thenReturn(result);
        for (String prefix : new String[]{"/contracts", "/api/v1/contracts"}) {
            mvc.perform(post(prefix + "/documents/pdf/integrity-check")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.result.status").value("MATCH"));
        }
    }
    @Test void checkedContentHasSafePdfHeadersAndExactBytes() throws Exception {
        byte[] bytes = "%PDF-content".getBytes(StandardCharsets.UTF_8);
        when(service.content("pdf")).thenReturn(new ContractDocumentIntegrityService.VerifiedPdf(bytes, "hop-dong.pdf", result));
        mvc.perform(get("/api/v1/contracts/documents/pdf/content")).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andExpect(content().bytes(bytes))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Document-Integrity", "MATCH"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
        mvc.perform(get("/contracts/documents/pdf/content?download=true")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        mvc.perform(post("/api/v1/contracts/documents/pdf/content")).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void mismatchedFileNeverReturnsPdfBytes() throws Exception {
        when(service.content("pdf")).thenThrow(new AppException(BlockchainProofError.DOCUMENT_INTEGRITY_MISMATCH));
        mvc.perform(get("/contracts/documents/pdf/content")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6083)).andExpect(jsonPath("$.result").doesNotExist());
    }
    @Test void unavailableBlockchainNeverFallsBackToRawStorageUrl() throws Exception {
        when(service.content("pdf")).thenThrow(new AppException(BlockchainProofError.DOCUMENT_CONTENT_UNAVAILABLE));
        mvc.perform(get("/contracts/documents/pdf/content")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(6084));
    }
}
