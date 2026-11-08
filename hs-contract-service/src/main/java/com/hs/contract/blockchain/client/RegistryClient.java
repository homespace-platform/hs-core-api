package com.hs.contract.blockchain.client;

import java.time.Instant;
import java.util.Optional;

public interface RegistryClient {
    record ChainProof(String documentHash, Instant registeredAt) {
        public boolean exists() { return documentHash != null && !documentHash.equals("0x" + "0".repeat(64)); }
    }

    // Raw transaction đã ký chỉ dùng nội bộ; không trả record này qua API/log.
    record SignedSubmission(String signerAddress, String nonce, String rawTransaction, String transactionHash) {
        @Override public String toString() { return "SignedSubmission[redacted]"; }
    }

    record Registration(String transactionHash, String recorder) {}
    record Receipt(long blockNumber, String blockHash, Instant registeredAt, boolean finalized) {}

    ChainProof readDocument(long chainId, String registryAddress, String onChainDocumentId);
    String signerAddress();
    SignedSubmission prepare(long chainId, String registryAddress, String onChainDocumentId, String documentHash);
    void broadcast(long chainId, String registryAddress, SignedSubmission submission);
    Optional<Receipt> receipt(long chainId, String registryAddress, String onChainDocumentId,
            String documentHash, String recorder, String transactionHash);
    Optional<Registration> findRegistration(long chainId, String registryAddress, String onChainDocumentId,
            String documentHash);
}
