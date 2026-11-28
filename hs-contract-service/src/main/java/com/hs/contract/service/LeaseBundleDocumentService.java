package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.document.InternalDocumentError;
import com.hs.common.document.InternalDocumentFile;
import com.hs.contract.blockchain.api.ContractDocumentIntegrityService;
import com.hs.contract.deposit.DepositAgreementService;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.constant.ContractDocumentType;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.model.constant.DocumentGenerationStatus;
import com.hs.contract.model.constant.DocumentPurpose;
import com.hs.contract.repository.ContractDocumentRepository;
import com.hs.contract.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Notification-only: the signed lease PDF plus the signed deposit agreement PDF of the same rental, each verified
 * with the same integrity check as the user-facing download. Deliberately not transactional: the integrity check
 * persists its own result.
 */
@Service
@RequiredArgsConstructor
public class LeaseBundleDocumentService {
    /** {@link ContractDocumentIntegrityService} codes that mean the stored file cannot be trusted. */
    private static final int INTEGRITY_MISMATCH = 6083, CONTENT_MISSING = 6085;
    private final ContractRepository contracts;
    private final ContractDocumentRepository documents;
    private final DepositAgreementService depositAgreements;
    private final ContractDocumentIntegrityService integrity;

    public List<InternalDocumentFile> bundle(String leaseId, String recipientId) {
        Contract lease = contracts.findById(leaseId).orElseThrow(InternalDocumentError.NOT_FOUND::exception);
        if (lease.getParentContractId() != null || DepositAgreementService.isDeposit(lease)) {
            throw InternalDocumentError.NOT_FOUND.exception();
        }
        if (recipientId == null || !recipientId.equals(lease.getLandlordId()) && !recipientId.equals(lease.getTenantId())) {
            throw InternalDocumentError.RECIPIENT_FORBIDDEN.exception();
        }
        if (lease.getSignedAt() == null) throw InternalDocumentError.NOT_READY.exception();
        // Lease signing requires the deposit agreement to be signed or exempt, so an unsigned/absent one means no deposit.
        Contract deposit = depositAgreements.find(lease.getRentalRequestId())
                .filter(d -> d.getStatus() == ContractStatus.ACTIVE && d.getSignedAt() != null).orElse(null);
        return Stream.of(file(lease, "hop-dong-thue.pdf", recipientId), deposit == null ? null : file(deposit, "hop-dong-coc.pdf", recipientId))
                .filter(java.util.Objects::nonNull).toList();
    }

    private InternalDocumentFile file(Contract contract, String name, String recipientId) {
        ContractDocument document = contract.getCurrentRevisionId() == null ? null
                : documents.findByContractIdAndRevisionId(contract.getId(), contract.getCurrentRevisionId()).stream()
                .filter(d -> d.getPurpose() == DocumentPurpose.SIGNED_FINAL && d.getDocumentType() == ContractDocumentType.PDF
                        && d.getStatus() == DocumentGenerationStatus.READY && d.getStorageObjectId() != null && !d.getStorageObjectId().isBlank())
                .max(Comparator.comparing(ContractDocument::getGeneratedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ContractDocument::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (document == null) throw InternalDocumentError.NOT_READY.exception();
        try {
            return new InternalDocumentFile(name, integrity.contentForParty(document.getId(), recipientId).bytes());
        } catch (AppException failure) {
            if (failure.getCode() == INTEGRITY_MISMATCH || failure.getCode() == CONTENT_MISSING) throw InternalDocumentError.INTEGRITY_FAILED.exception();
            // Provider/chain outage (6084) or forbidden: transient outage is retried by the caller, never sent unverified.
            if (failure.getStatusCode().value() == 403) throw InternalDocumentError.RECIPIENT_FORBIDDEN.exception();
            throw InternalDocumentError.NOT_READY.exception();
        }
    }
}
