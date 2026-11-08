package com.hs.contract.blockchain.api;

import java.time.Instant;

public record VerifyDocumentHashResponse(
        String documentId, boolean matches, boolean registered, String documentHash,
        Instant registeredAt, long chainId, String registryAddress, String onChainDocumentId) {
}
