package com.hs.api.controller.contract;

import com.hs.contract.blockchain.api.*;
import com.hs.api.advice.handler.GlobalException;
import com.hs.common.advice.entity.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContractBlockchainProofControllerTest {
    private final ContractDocumentProofQueryService proofs = mock(ContractDocumentProofQueryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ContractBlockchainProofController(proofs))
            .setControllerAdvice(new GlobalException()).build();

    @Test void bothRouteAliasesUseTheDocumentScopedReadAndDisableCaching() throws Exception {
        when(proofs.get("pdf")).thenReturn(new BlockchainProofResponse("pdf", "contract", "revision", "NOT_AVAILABLE",
                null, null, null, null, null, null, null, null, null, null, "Chưa có proof", null, null));
        for (String prefix : new String[]{"/contracts", "/api/v1/contracts"}) {
            mvc.perform(get(prefix + "/documents/pdf/blockchain-proof"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.result.documentId").value("pdf"))
                    .andExpect(jsonPath("$.result.status").value("NOT_AVAILABLE"));
        }
    }

    @Test void invalidHashBodyCannotReachTheBlockchainService() throws Exception {
        mvc.perform(post("/api/v1/contracts/documents/pdf/blockchain-proof/verify")
                        .contentType("application/json").content("{\"documentHash\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(proofs);
    }

    @Test void verificationOnlyAcceptsTheHashNotAPdfUpload() throws Exception {
        String hash = "0x" + "a".repeat(64);
        when(proofs.verify("pdf", hash)).thenReturn(new VerifyDocumentHashResponse("pdf", true, true, hash,
                null, 11155111, "0x" + "2".repeat(40), "0x" + "1".repeat(64)));
        mvc.perform(post("/contracts/documents/pdf/blockchain-proof/verify")
                        .contentType("application/json").content("{\"documentHash\":\"" + hash + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.matches").value(true))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void rpcOutageUsesTheExistingErrorEnvelopeAndNeverReturnsMatchesFalse() throws Exception {
        String hash = "0x" + "a".repeat(64);
        when(proofs.verify("pdf", hash)).thenThrow(new AppException(BlockchainProofError.BLOCKCHAIN_UNAVAILABLE));
        mvc.perform(post("/contracts/documents/pdf/blockchain-proof/verify")
                        .contentType("application/json").content("{\"documentHash\":\"" + hash + "\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(6081))
                .andExpect(jsonPath("$.result").doesNotExist());
    }
}
