package com.hs.contract.blockchain.worker;

import com.hs.contract.blockchain.client.BlockchainException;
import com.hs.contract.blockchain.client.RegistryClient;
import com.hs.contract.blockchain.config.BlockchainProperties;
import com.hs.contract.blockchain.persistence.ContractDocumentProof;
import com.hs.contract.blockchain.persistence.ContractDocumentProofRepository;
import com.hs.contract.blockchain.persistence.DocumentProofHash;
import com.hs.contract.blockchain.persistence.ProofStatus;
import com.hs.storage.service.StorageService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContractDocumentProofWorker {
    private final BlockchainProperties properties;
    private final ContractDocumentProofRepository proofs;
    private final RegistryClient registry;
    private final StorageService storage;
    private final BlockchainSenderLeaseStore leases;
    private final PlatformTransactionManager transactions;

    public void tick() {
        if (!properties.isEnabled() || !properties.isConfigured()) return;
        String leaseId = "contract-document-proof-sender";
        String token = UUID.randomUUID().toString();
        try {
            if (!leases.acquire(leaseId, token)) return;
            List<ContractDocumentProof> submitted = proofs.findByStatusOrderByCreatedAtAsc(ProofStatus.SUBMITTED);
            // Chỉ cấp nonce tiếp theo sau khi mọi giao dịch đã gửi có receipt hợp lệ trong block.
            Instant now = Instant.now();
            ContractDocumentProof proof = null;
            for (ContractDocumentProof candidate : submitted) {
                if (candidate.getNextAttemptAt() == null || !candidate.getNextAttemptAt().isAfter(now)) {
                    proof = candidate;
                    break;
                }
                if (candidate.getBlockNumber() == null || candidate.getErrorCode() != null) return;
            }
            if (proof == null) proof = proofs.findDue(now, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
            if (proof == null || (proof.getNextAttemptAt() != null && proof.getNextAttemptAt().isAfter(Instant.now()))) return;
            process(proof, leaseId, token);
        } catch (BlockchainException safe) {
            log.warn("BLOCKCHAIN_WORKER_UNAVAILABLE code={}", safe.getCode());
        } catch (RuntimeException failure) {
            // Không log message/cause từ SDK: chúng có thể chứa RPC URL/API key.
            log.error("BLOCKCHAIN_WORKER_UNAVAILABLE code=INTERNAL_ERROR");
        } finally {
            try {
                leases.release(leaseId, token);
            } catch (RuntimeException unavailable) {
                log.warn("BLOCKCHAIN_WORKER_LEASE_RELEASE_UNAVAILABLE");
            }
        }
    }

    void process(ContractDocumentProof proof, String leaseId, String token) {
        try {
            if (!mutate(proof.getDocumentId(), leaseId, token, current -> {
                current.setAttempts(current.getAttempts() + 1);
                current.setErrorCode(null);
                current.setErrorMessage(null);
            })) return;
            RegistryClient.ChainProof stored = registry.readDocument(proof.getChainId(), proof.getRegistryAddress(), proof.getOnChainDocumentId());
            if (stored.exists() && !stored.documentHash().equalsIgnoreCase(proof.getDocumentHash())) {
                throw new BlockchainException("DOCUMENT_HASH_CONFLICT", "ID đã ghi nhận hash khác; cần đối soát, không tự tạo ID mới.", true);
            }
            if (stored.exists() && proof.getTransactionHash() == null) {
                RegistryClient.Registration existing = registry.findRegistration(proof.getChainId(), proof.getRegistryAddress(),
                        proof.getOnChainDocumentId(), proof.getDocumentHash())
                        .orElseThrow(() -> new BlockchainException("REGISTRATION_NOT_LOCATED", "Hash đã tồn tại; đang tìm giao dịch gốc để đối soát."));
                if (!mutate(proof.getDocumentId(), leaseId, token, current -> {
                    current.setTransactionHash(existing.transactionHash());
                    current.setSignerAddress(existing.recorder());
                    current.setStatus(ProofStatus.SUBMITTED);
                    current.setSubmittedAt(Instant.now());
                })) return;
                proof.setTransactionHash(existing.transactionHash());
                proof.setSignerAddress(existing.recorder());
                proof.setStatus(ProofStatus.SUBMITTED);
            }
            if (proof.getTransactionHash() != null) {
                var receipt = registry.receipt(proof.getChainId(), proof.getRegistryAddress(), proof.getOnChainDocumentId(),
                        proof.getDocumentHash(), proof.getSignerAddress(), proof.getTransactionHash());
                if (receipt.isPresent()) {
                    RegistryClient.Receipt result = receipt.get();
                    if (result.finalized() && proof.getCaseId() != null && proof.getDocumentKind() != null) {
                        RegistryClient.ChainLineage lineage = registry.readLineage(proof.getChainId(),
                                proof.getRegistryAddress(), proof.getOnChainDocumentId());
                        if (!proof.getCaseId().equalsIgnoreCase(lineage.caseId())
                                || proof.getDocumentKind() != lineage.documentKind()) {
                            throw new BlockchainException("DOCUMENT_LINEAGE_CONFLICT", "Mã hồ sơ hoặc loại văn bản on-chain không khớp proof.", true);
                        }
                    }
                    mutate(proof.getDocumentId(), leaseId, token, current -> {
                        current.setBlockNumber(result.blockNumber());
                        current.setBlockHash(result.blockHash());
                        current.setRegisteredAt(result.registeredAt());
                        current.setStatus(result.finalized() ? ProofStatus.CONFIRMED : ProofStatus.SUBMITTED);
                        current.setConfirmedAt(result.finalized() ? Instant.now() : null);
                        current.setNextAttemptAt(result.finalized() ? Instant.now() : nextAttempt());
                    });
                    return;
                }
                if (proof.getBlockNumber() != null && !mutate(proof.getDocumentId(), leaseId, token, current -> {
                    current.setBlockNumber(null);
                    current.setBlockHash(null);
                    current.setRegisteredAt(null);
                })) return;
                if (!stored.exists() && proof.getSignedRawTransaction() != null) {
                    broadcast(proof, leaseId, token);
                } else {
                    reschedule(proof.getDocumentId(), leaseId, token, null);
                }
                return;
            }

            byte[] pdf = storage.downloadDirect(proof.getStorageObjectId(), 50L * 1024 * 1024);
            if (!DocumentProofHash.sha256(pdf).equalsIgnoreCase(proof.getDocumentHash())) {
                throw new BlockchainException("SOURCE_PDF_CHANGED", "PDF lưu trữ không khớp hash lúc ký; cần đối soát.", true);
            }
            RegistryClient.SignedSubmission signed = proof.getCaseId() == null || proof.getDocumentKind() == null
                    ? registry.prepare(proof.getChainId(), proof.getRegistryAddress(), proof.getOnChainDocumentId(), proof.getDocumentHash())
                    : registry.prepare(proof.getChainId(), proof.getRegistryAddress(), proof.getOnChainDocumentId(),
                            proof.getDocumentHash(), proof.getCaseId(), proof.getDocumentKind());
            // Lưu bytes, nonce và hash trước broadcast để đóng khe restart/timeout sau khi gửi.
            if (!mutate(proof.getDocumentId(), leaseId, token, current -> {
                current.setSignerAddress(signed.signerAddress());
                current.setNonce(signed.nonce());
                current.setSignedRawTransaction(signed.rawTransaction());
                current.setTransactionHash(signed.transactionHash());
                current.setStatus(ProofStatus.SUBMITTED);
                current.setSubmittedAt(Instant.now());
                current.setNextAttemptAt(nextAttempt());
            })) return;
            proof.setSignerAddress(signed.signerAddress());
            proof.setNonce(signed.nonce());
            proof.setSignedRawTransaction(signed.rawTransaction());
            proof.setTransactionHash(signed.transactionHash());
            proof.setStatus(ProofStatus.SUBMITTED);
            broadcast(proof, leaseId, token);
        } catch (BlockchainException safe) {
            reschedule(proof.getDocumentId(), leaseId, token, safe);
        } catch (RuntimeException failure) {
            reschedule(proof.getDocumentId(), leaseId, token,
                    new BlockchainException("PROCESSING_UNAVAILABLE", "Chưa xử lý được proof; hệ thống sẽ đối soát và thử lại."));
        }
    }

    private void broadcast(ContractDocumentProof proof, String leaseId, String token) {
        // Worker hết lease không được gửi giao dịch vừa chuẩn bị nhưng chưa lưu được.
        if (!mutate(proof.getDocumentId(), leaseId, token, current -> current.setNextAttemptAt(nextAttempt()))) return;
        registry.broadcast(proof.getChainId(), proof.getRegistryAddress(),
                new RegistryClient.SignedSubmission(proof.getSignerAddress(), proof.getNonce(),
                        proof.getSignedRawTransaction(), proof.getTransactionHash()));
    }

    private void reschedule(String documentId, String leaseId, String token, BlockchainException failure) {
        mutate(documentId, leaseId, token, current -> {
            current.setNextAttemptAt(nextAttempt());
            if (failure != null) {
                current.setErrorCode(failure.getCode());
                current.setErrorMessage(failure.getMessage());
                if (failure.isTerminal()) {
                    current.setStatus(ProofStatus.FAILED);
                    current.setNextAttemptAt(Instant.now());
                }
            }
        });
    }

    private boolean mutate(String documentId, String leaseId, String token, Consumer<ContractDocumentProof> change) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(tx -> {
            if (!leases.ownsForUpdate(leaseId, token)) return false;
            var current = proofs.findByIdForUpdate(documentId).orElse(null);
            if (current == null || current.getStatus() == ProofStatus.CONFIRMED || current.getStatus() == ProofStatus.FAILED) return false;
            change.accept(current);
            current.setUpdatedAt(Instant.now());
            proofs.saveAndFlush(current);
            return true;
        }));
    }

    private Instant nextAttempt() { return Instant.now().plusSeconds(Math.max(5, properties.getRetryDelaySeconds())); }
}
