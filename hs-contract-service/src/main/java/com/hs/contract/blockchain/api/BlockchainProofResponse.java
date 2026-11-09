package com.hs.contract.blockchain.api;

import java.time.Instant;

/** Chỉ trả dữ liệu tra cứu; không đưa private key, nonce hay raw transaction ra API. */
public record BlockchainProofResponse(
        String documentId, String contractId, String revisionId, String status,
        String documentHash, String onChainDocumentId, Long chainId, String registryAddress,
        String transactionHash, Long blockNumber, String blockHash, Instant registeredAt,
        Instant confirmedAt, String errorCode, String message, String caseId, Integer documentKind) {
}
