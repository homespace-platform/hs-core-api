package com.hs.contract.blockchain.api;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.contract.blockchain.client.RegistryClient;
import com.hs.contract.blockchain.persistence.ContractDocumentProof;
import com.hs.contract.blockchain.persistence.ContractDocumentProofRepository;
import com.hs.contract.dto.response.ContractDocumentResponse;
import com.hs.contract.model.constant.ContractDocumentType;
import com.hs.contract.model.constant.DocumentGenerationStatus;
import com.hs.contract.model.constant.DocumentPurpose;
import com.hs.contract.service.ContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContractDocumentProofQueryService {
    private static final String ZERO_HASH = "0x" + "0".repeat(64);
    private final ContractService contracts;
    private final ContractDocumentProofRepository proofs;
    private final RegistryClient registry;

    public BlockchainProofResponse get(String documentId) {
        var document = authorizedDocument(documentId);
        var proof = proofs.findById(documentId).orElse(null);
        if (proof == null) {
            return new BlockchainProofResponse(document.getId(), document.getContractId(), document.getRevisionId(),
                    "NOT_AVAILABLE", null, null, null, null, null, null, null, null, null, null,
                    "Chưa có proof blockchain cho tài liệu này. Proof được tạo cho PDF cuối khi tính năng được bật và hai bên ký hoàn tất.", null, null);
        }
        requireSameArtifact(document, proof);
        String message = switch (proof.getStatus()) {
            case PENDING -> "Đang chờ ghi nhận blockchain; PDF đã ký vẫn được lưu.";
            case SUBMITTED -> "Đang đối soát giao dịch và chờ blockchain xác nhận hoàn tất.";
            case CONFIRMED -> "Hash PDF đã được ghi nhận trên blockchain.";
            case FAILED -> "Chưa hoàn tất ghi nhận blockchain; PDF đã ký vẫn được lưu. Cần hệ thống đối soát.";
        };
        return new BlockchainProofResponse(document.getId(), document.getContractId(), document.getRevisionId(),
                proof.getStatus().name(), proof.getDocumentHash(), proof.getOnChainDocumentId(), proof.getChainId(),
                proof.getRegistryAddress(), proof.getTransactionHash(), proof.getBlockNumber(), proof.getBlockHash(),
                proof.getRegisteredAt(), proof.getConfirmedAt(), proof.getErrorCode(), message,
                proof.getCaseId(), proof.getDocumentKind());
    }

    public VerifyDocumentHashResponse verify(String documentId, String candidateHash) {
        var document = authorizedDocument(documentId);
        if (candidateHash == null || !candidateHash.matches("^0x[0-9a-fA-F]{64}$")) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Hash phải gồm 0x và 64 ký tự hex.");
        }
        var proof = proofs.findById(documentId)
                .orElseThrow(() -> new AppException(BlockchainProofError.PROOF_NOT_AVAILABLE));
        requireSameArtifact(document, proof);
        // Đọc on-chain thật; lỗi RPC không được biến thành kết luận file đã bị sửa.
        RegistryClient.ChainProof stored;
        try {
            stored = registry.readDocument(proof.getChainId(), proof.getRegistryAddress(), proof.getOnChainDocumentId());
        } catch (RuntimeException unavailable) {
            // Lỗi khởi tạo HTTP/SDK cũng có thể chứa URL có API key; không chuyển cause ra handler/log.
            throw new AppException(BlockchainProofError.BLOCKCHAIN_UNAVAILABLE);
        }
        boolean registered = !ZERO_HASH.equalsIgnoreCase(stored.documentHash());
        if (registered && !proof.getDocumentHash().equalsIgnoreCase(stored.documentHash())) {
            throw new AppException(BlockchainProofError.PROOF_CONFLICT);
        }
        return new VerifyDocumentHashResponse(documentId,
                registered && stored.documentHash().equalsIgnoreCase(candidateHash), registered,
                stored.documentHash().toLowerCase(Locale.ROOT), stored.registeredAt(), proof.getChainId(),
                proof.getRegistryAddress(), proof.getOnChainDocumentId());
    }

    private ContractDocumentResponse authorizedDocument(String documentId) {
        var context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        // Tái sử dụng quyền đọc tài liệu hiện có, bao gồm giới hạn truy cập bản nháp.
        return contracts.getDocument(documentId);
    }

    private void requireSameArtifact(ContractDocumentResponse document, ContractDocumentProof proof) {
        if (document.getDocumentType() != ContractDocumentType.PDF
                || document.getPurpose() != DocumentPurpose.SIGNED_FINAL
                || document.getStatus() != DocumentGenerationStatus.READY
                || !Objects.equals(document.getId(), proof.getDocumentId())
                || !Objects.equals(document.getContractId(), proof.getContractId())
                || !Objects.equals(document.getRevisionId(), proof.getRevisionId())
                || !Objects.equals(document.getStorageObjectId(), proof.getStorageObjectId())) {
            throw new AppException(BlockchainProofError.PROOF_CONFLICT);
        }
    }
}
