package com.hs.contract.blockchain.persistence;

import com.hs.contract.blockchain.config.BlockchainProperties;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.constant.ContractDocumentType;
import com.hs.contract.model.constant.DocumentGenerationStatus;
import com.hs.contract.model.constant.DocumentPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContractDocumentProofEnqueuer {
    private final ContractDocumentProofRepository proofs;
    private final BlockchainProperties properties;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ZERO_HASH = "0x" + "0".repeat(64);

    /** Job phải commit/rollback cùng PDF cuối; worker chỉ gọi blockchain sau transaction này. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Contract contract, ContractDocument document, String documentHash) {
        if (!properties.isEnabled() || !properties.isConfigured() || !supports(contract, document)) return;
        if (documentHash == null || !documentHash.matches("0x[0-9a-fA-F]{64}")
                || ZERO_HASH.equalsIgnoreCase(documentHash)) {
            throw new IllegalArgumentException("A nonzero SHA-256 PDF hash is required");
        }
        String normalizedHash = documentHash.toLowerCase(Locale.ROOT);
        var existing = proofs.findById(document.getId());
        if (existing.isPresent()) {
            ContractDocumentProof saved = existing.get();
            if (!normalizedHash.equals(saved.getDocumentHash())
                    || !Objects.equals(document.getStorageObjectId(), saved.getStorageObjectId())
                    || !Objects.equals(document.getRevisionId(), saved.getRevisionId())
                    || !Objects.equals(contract.getId(), saved.getContractId())) {
                throw new IllegalStateException("The final PDF already has a different blockchain proof identity");
            }
            return;
        }
        byte[] opaqueId = new byte[32];
        do {
            RANDOM.nextBytes(opaqueId);
        } while (ZERO_HASH.equals("0x" + HexFormat.of().formatHex(opaqueId)));
        Instant now = Instant.now();
        ContractDocumentProof proof = new ContractDocumentProof();
        proof.setDocumentId(document.getId());
        proof.setContractId(contract.getId());
        proof.setRevisionId(document.getRevisionId());
        proof.setStorageObjectId(document.getStorageObjectId());
        proof.setOnChainDocumentId("0x" + HexFormat.of().formatHex(opaqueId));
        proof.setDocumentHash(normalizedHash);
        proof.setChainId(properties.getChainId());
        proof.setRegistryAddress(properties.getRegistryAddress().toLowerCase(Locale.ROOT));
        proof.setStatus(ProofStatus.PENDING);
        proof.setCreatedAt(now);
        proof.setUpdatedAt(now);
        proof.setNextAttemptAt(now);
        proofs.save(proof);
    }

    public static boolean supports(Contract contract, ContractDocument document) {
        if (contract == null || document == null) return false;
        String kind = contract.getAgreementKind();
        boolean supported = contract.getParentContractId() == null
                ? kind == null || "LEASE".equals(kind) || "DEPOSIT".equals(kind)
                : "AMENDMENT".equals(kind);
        return supported
                && document.getId() != null && document.getStorageObjectId() != null
                && Objects.equals(contract.getId(), document.getContractId())
                && document.getRevisionId() != null
                && Objects.equals(contract.getCurrentRevisionId(), document.getRevisionId())
                && document.getDocumentType() == ContractDocumentType.PDF
                && document.getPurpose() == DocumentPurpose.SIGNED_FINAL
                && document.getStatus() == DocumentGenerationStatus.READY;
    }
}
