package com.hs.contract.blockchain.api;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.blockchain.client.RegistryClient;
import com.hs.contract.blockchain.persistence.ContractDocumentProof;
import com.hs.contract.blockchain.persistence.ContractDocumentProofRepository;
import com.hs.contract.blockchain.persistence.DocumentProofHash;
import com.hs.contract.blockchain.persistence.ProofStatus;
import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.constant.ContractDocumentType;
import com.hs.contract.model.constant.DocumentGenerationStatus;
import com.hs.contract.model.constant.DocumentPurpose;
import com.hs.contract.repository.ContractDocumentRepository;
import com.hs.contract.service.ContractService;
import com.hs.storage.advice.entity.enums.StorageErrorCode;
import com.hs.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractDocumentIntegrityService {
    private static final long MAX_PDF_BYTES = 50L * 1024 * 1024;
    private final ContractService contracts;
    private final ContractDocumentRepository documents;
    private final ContractDocumentProofRepository proofs;
    private final StorageService storage;
    private final RegistryClient registry;

    public DocumentIntegrityResponse check(String documentId) {
        return inspect(documentId).integrity();
    }

    public VerifiedPdf content(String documentId) {
        Inspection checked = inspect(documentId);
        return switch (checked.integrity().status()) {
            case "MATCH", "PENDING", "NOT_AVAILABLE" -> new VerifiedPdf(checked.bytes(), checked.fileName(), checked.integrity());
            case "MISMATCH" -> throw new AppException(BlockchainProofError.DOCUMENT_INTEGRITY_MISMATCH);
            case "MISSING" -> throw new AppException(BlockchainProofError.DOCUMENT_CONTENT_MISSING);
            default -> throw new AppException(BlockchainProofError.DOCUMENT_CONTENT_UNAVAILABLE);
        };
    }

    private Inspection inspect(String documentId) {
        var user = UserContextHolder.get();
        if (user == null || user.userId() == null || user.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        var authorized = contracts.getDocumentMetadata(documentId);
        ContractDocument document = documents.findById(documentId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_DOCUMENT_NOT_FOUND));
        if (!Objects.equals(document.getContractId(), authorized.getContractId())
                || !Objects.equals(document.getRevisionId(), authorized.getRevisionId())
                || !Objects.equals(document.getStorageObjectId(), authorized.getStorageObjectId())
                || document.getDocumentType() != ContractDocumentType.PDF
                || document.getPurpose() != DocumentPurpose.SIGNED_FINAL
                || document.getStatus() != DocumentGenerationStatus.READY) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Chỉ kiểm tra PDF cuối đã ký đủ hai bên.");
        }
        ContractDocumentProof proof = proofs.findById(documentId).orElse(null);
        if (proof != null && (!Objects.equals(document.getId(), proof.getDocumentId())
                || !Objects.equals(document.getContractId(), proof.getContractId())
                || !Objects.equals(document.getRevisionId(), proof.getRevisionId())
                || !Objects.equals(document.getStorageObjectId(), proof.getStorageObjectId())
                || (document.getSignedSha256() != null && !sameHash(document.getSignedSha256(), proof.getDocumentHash())))) {
            return outcome(document, "MISMATCH", null, null, null, "Dữ liệu proof không khớp tài liệu đã ký.");
        }
        byte[] bytes;
        try {
            if (document.getSignedS3VersionId() != null) {
                var object = storage.getById(document.getStorageObjectId());
                if (!document.getSignedS3VersionId().equals(object.s3VersionId())) {
                    return outcome(document, "MISMATCH", null, null, null, "Phiên bản lưu trữ khác bản đã ký.");
                }
            }
            bytes = storage.downloadDirect(document.getStorageObjectId(), MAX_PDF_BYTES);
        } catch (AppException failure) {
            boolean missing = failure.getCode() == StorageErrorCode.STORAGE_OBJECT_NOT_FOUND.getCode()
                    || failure.getCode() == StorageErrorCode.STORAGE_UPLOAD_NOT_FOUND.getCode();
            return outcome(document, missing ? "MISSING" : "UNAVAILABLE", null, null, null,
                    missing ? "Không tìm thấy PDF đã lưu; cần khôi phục tài liệu." : "Chưa đọc được PDF để xác thực; vui lòng thử lại.");
        } catch (RuntimeException failure) {
            return outcome(document, "UNAVAILABLE", null, null, null, "Chưa đọc được PDF để xác thực; vui lòng thử lại.");
        }
        String actual = DocumentProofHash.sha256(bytes);
        String expected = document.getSignedSha256() != null ? document.getSignedSha256()
                : proof != null ? proof.getDocumentHash() : null;
        if (expected != null && !sameHash(actual, expected)) {
            return outcome(document, "MISMATCH", actual, null, null, "PDF không khớp hash của bản đã ký.");
        }
        if (proof != null && proof.getStatus() == ProofStatus.CONFIRMED) {
            RegistryClient.ChainProof onChain;
            try {
                onChain = registry.readDocument(proof.getChainId(), proof.getRegistryAddress(), proof.getOnChainDocumentId());
            } catch (RuntimeException unavailable) {
                return outcome(document, "UNAVAILABLE", actual, null, null, "Chưa kết nối được blockchain để xác thực PDF; vui lòng thử lại.");
            }
            if (!sameHash(actual, onChain.documentHash()) || !sameHash(proof.getDocumentHash(), onChain.documentHash())) {
                return outcome(document, "MISMATCH", actual, onChain.documentHash(), null, "PDF không khớp bằng chứng trên blockchain.");
            }
            return outcome(document, "MATCH", actual, onChain.documentHash(), bytes, "PDF khớp bản đã ghi nhận trên blockchain.");
        }
        if (expected == null) {
            return outcome(document, "NOT_AVAILABLE", actual, null, bytes, "Tài liệu cũ chưa có hash cố định hoặc proof blockchain; chưa xác thực tính toàn vẹn.");
        }
        return outcome(document, "PENDING", actual, null, bytes, proof != null && proof.getStatus() == ProofStatus.FAILED
                ? "PDF khớp hash lúc ký; ghi nhận blockchain chưa thành công, cần đối soát."
                : "PDF khớp hash lúc ký; chưa được xác nhận trên blockchain.");
    }

    private Inspection outcome(ContractDocument document, String status, String actual, String chainHash,
            byte[] bytes, String message) {
        Instant checkedAt = Instant.now();
        documents.updateIntegrityResult(document.getId(), status, checkedAt, message);
        log.info("CONTRACT_DOCUMENT_INTEGRITY document={} status={} checkedAt={}", document.getId(), status, checkedAt);
        return new Inspection(new DocumentIntegrityResponse(document.getId(), status, actual, chainHash, checkedAt, message),
                bytes, document.getFileName());
    }

    private static boolean sameHash(String a, String b) { return a != null && b != null && a.equalsIgnoreCase(b); }

    private record Inspection(DocumentIntegrityResponse integrity, byte[] bytes, String fileName) { }
    /** Chỉ tạo sau khi kiểm tra hoàn tất; controller trả chính bytes này, không đọc S3 lần hai. */
    public record VerifiedPdf(byte[] bytes, String fileName, DocumentIntegrityResponse integrity) { }
}
