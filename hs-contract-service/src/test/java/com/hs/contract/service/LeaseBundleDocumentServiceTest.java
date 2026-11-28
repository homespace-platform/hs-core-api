package com.hs.contract.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hs.common.advice.entity.AppException;
import com.hs.common.document.InternalDocumentError;
import com.hs.contract.blockchain.api.BlockchainProofError;
import com.hs.contract.blockchain.api.ContractDocumentIntegrityService;
import com.hs.contract.deposit.DepositAgreementService;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.ContractDocumentRepository;
import com.hs.contract.repository.ContractRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeaseBundleDocumentServiceTest {
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final ContractDocumentRepository documents = mock(ContractDocumentRepository.class);
    private final DepositAgreementService deposits = mock(DepositAgreementService.class);
    private final ContractDocumentIntegrityService integrity = mock(ContractDocumentIntegrityService.class);
    private final LeaseBundleDocumentService service = new LeaseBundleDocumentService(contracts, documents, deposits, integrity);
    private Contract lease, deposit;

    @BeforeEach void setup() {
        lease = Contract.builder().id("lease").rentalRequestId("rental").landlordId("landlord").tenantId("tenant")
                .currentRevisionId("lr").status(ContractStatus.ACTIVE).signedAt(Instant.now()).build();
        deposit = Contract.builder().id("dep").rentalRequestId("rental").landlordId("landlord").tenantId("tenant")
                .currentRevisionId("dr").agreementKind("DEPOSIT").status(ContractStatus.ACTIVE).signedAt(Instant.now()).build();
        when(contracts.findById("lease")).thenReturn(Optional.of(lease));
        when(deposits.find("rental")).thenReturn(Optional.of(deposit));
        when(documents.findByContractIdAndRevisionId("lease", "lr")).thenReturn(List.of(doc("lease-pdf", "lease", "lr")));
        when(documents.findByContractIdAndRevisionId("dep", "dr")).thenReturn(List.of(doc("dep-pdf", "dep", "dr")));
        for (String party : new String[]{"tenant", "landlord"}) {
            when(integrity.contentForParty("lease-pdf", party)).thenReturn(pdf("lease"));
            when(integrity.contentForParty("dep-pdf", party)).thenReturn(pdf("deposit"));
        }
    }

    private static ContractDocument doc(String id, String contractId, String revisionId) {
        return ContractDocument.builder().id(id).contractId(contractId).revisionId(revisionId)
                .documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.SIGNED_FINAL)
                .status(DocumentGenerationStatus.READY).storageObjectId("s-" + id).build();
    }

    private static ContractDocumentIntegrityService.VerifiedPdf pdf(String text) {
        return new ContractDocumentIntegrityService.VerifiedPdf(text.getBytes(), "x.pdf", null);
    }

    private int failure(String id, String recipient) {
        return assertThrows(AppException.class, () -> service.bundle(id, recipient)).getCode();
    }

    @Test void leaseAndDepositArriveTogetherForEitherParty() {
        for (String party : new String[]{"tenant", "landlord"}) {
            var files = service.bundle("lease", party);
            assertEquals(List.of("hop-dong-thue.pdf", "hop-dong-coc.pdf"), files.stream().map(f -> f.fileName()).toList());
            assertArrayEquals("lease".getBytes(), files.get(0).bytes());
            assertArrayEquals("deposit".getBytes(), files.get(1).bytes());
        }
    }

    @Test void noDepositOrUnsignedDepositMeansLeaseOnly() {
        when(deposits.find("rental")).thenReturn(Optional.empty());
        assertEquals(1, service.bundle("lease", "tenant").size());
        deposit.setSignedAt(null);
        when(deposits.find("rental")).thenReturn(Optional.of(deposit));
        assertEquals(1, service.bundle("lease", "tenant").size());
    }

    @Test void strangerIsDeniedBeforeAnyFileIsRead() {
        assertEquals(InternalDocumentError.RECIPIENT_FORBIDDEN.getCode(), failure("lease", "stranger"));
        assertEquals(InternalDocumentError.RECIPIENT_FORBIDDEN.getCode(), failure("lease", null));
        verifyNoInteractions(integrity);
    }

    @Test void unknownLeaseOrDepositIdIsNotFound() {
        assertEquals(InternalDocumentError.NOT_FOUND.getCode(), failure("nope", "tenant"));
        when(contracts.findById("dep")).thenReturn(Optional.of(deposit));
        assertEquals(InternalDocumentError.NOT_FOUND.getCode(), failure("dep", "tenant"));
    }

    @Test void leaseNotFullySignedOrPdfMissingIsNotReady() {
        lease.setSignedAt(null);
        assertEquals(InternalDocumentError.NOT_READY.getCode(), failure("lease", "tenant"));
        lease.setSignedAt(Instant.now());
        when(documents.findByContractIdAndRevisionId("lease", "lr")).thenReturn(List.of());
        assertEquals(InternalDocumentError.NOT_READY.getCode(), failure("lease", "tenant"));
    }

    @Test void anyFileFailingIntegrityFailsTheWholeBundle() {
        when(integrity.contentForParty("dep-pdf", "tenant")).thenThrow(new AppException(BlockchainProofError.DOCUMENT_INTEGRITY_MISMATCH));
        assertEquals(InternalDocumentError.INTEGRITY_FAILED.getCode(), failure("lease", "tenant"));
        doReturn(pdf("deposit")).when(integrity).contentForParty("dep-pdf", "tenant");
        when(integrity.contentForParty("lease-pdf", "tenant")).thenThrow(new AppException(BlockchainProofError.DOCUMENT_CONTENT_MISSING));
        assertEquals(InternalDocumentError.INTEGRITY_FAILED.getCode(), failure("lease", "tenant"));
    }

    @Test void providerOutageIsRetriableNeverSentUnverified() {
        when(integrity.contentForParty("lease-pdf", "tenant")).thenThrow(new AppException(BlockchainProofError.DOCUMENT_CONTENT_UNAVAILABLE));
        assertEquals(InternalDocumentError.NOT_READY.getCode(), failure("lease", "tenant"));
    }
}
