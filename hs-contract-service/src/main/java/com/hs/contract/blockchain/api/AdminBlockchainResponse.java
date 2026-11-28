package com.hs.contract.blockchain.api;

import java.time.Instant;
import java.util.Map;

public final class AdminBlockchainResponse {
    private AdminBlockchainResponse() {}
    public record Network(long chainId, String name, String registryAddress, boolean enabled, String explorerBaseUrl) {}
    public record Integrity(Map<String, Long> counts, String note) {}
    public record Summary(Instant asOf, Network network, Map<String, Long> counts, long stuck,
                          Long oldestOpenAgeSeconds, long retrying, Map<String, Long> byErrorCode,
                          long openIncidents, Instant senderLeaseHeldUntil, Integrity integrity, long ethUnsettled,
                          Long workerLastRunAgeSeconds, Map<String, String> sourceCapabilities) {}
    public record ProofRow(String documentId, String contractNumber, Integer documentKind, String status,
                           String transactionHash, Long blockNumber, Instant createdAt, Instant confirmedAt, String errorCode) {}
    public record ProofDetail(String documentId, String contractNumber, Integer documentKind, String status,
                              String documentHash, String onChainDocumentId, long chainId, String registryAddress,
                              String transactionHash, Long blockNumber, String blockHash, Instant registeredAt,
                              Instant createdAt, Instant confirmedAt, String errorCode, String caseId, int attempts,
                              Instant submittedAt) {}
    public record Verification(String documentId, String status, Boolean registered, Boolean matches,
                               Boolean conflict, String onChainHash, Instant registeredAt) {}
}
