package com.hs.contract.blockchain.api;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.blockchain.client.RegistryClient;
import com.hs.contract.blockchain.persistence.*;
import com.hs.contract.dto.response.ContractDocumentResponse;
import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.ContractDocumentRepository;
import com.hs.contract.service.ContractService;
import com.hs.storage.advice.entity.enums.StorageErrorCode;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractDocumentIntegrityServiceTest {
    private static final byte[] PDF = "%PDF-1.7 signed final".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = DocumentProofHash.sha256(PDF);
    private static final String OTHER = "0x" + "b".repeat(64);
    private final ContractService contracts = mock(ContractService.class);
    private final ContractDocumentRepository documents = mock(ContractDocumentRepository.class);
    private final ContractDocumentProofRepository proofs = mock(ContractDocumentProofRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final RegistryClient registry = mock(RegistryClient.class);
    private final ContractDocumentIntegrityService service = new ContractDocumentIntegrityService(contracts, documents, proofs, storage, registry);
    private ContractDocument document;
    private ContractDocumentProof proof;

    @BeforeEach void setup() {
        UserContextHolder.set(new UserContext("tenant", null));
        document = ContractDocument.builder().id("pdf").contractId("contract").revisionId("revision")
                .documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.SIGNED_FINAL)
                .status(DocumentGenerationStatus.READY).storageObjectId("storage").fileName("hop-dong.pdf")
                .signedSha256(HASH).signedS3VersionId("v1").build();
        when(contracts.getDocumentMetadata("pdf")).thenReturn(ContractDocumentResponse.builder()
                .id("pdf").contractId("contract").revisionId("revision").storageObjectId("storage").build());
        when(documents.findById("pdf")).thenReturn(Optional.of(document));
        StorageObjectResponse object = mock(StorageObjectResponse.class);
        when(object.s3VersionId()).thenReturn("v1"); when(storage.getById("storage")).thenReturn(object);
        when(storage.downloadDirect("storage", 50L * 1024 * 1024)).thenReturn(PDF);
        proof = new ContractDocumentProof(); proof.setDocumentId("pdf"); proof.setContractId("contract");
        proof.setRevisionId("revision"); proof.setStorageObjectId("storage"); proof.setDocumentHash(HASH);
        proof.setStatus(ProofStatus.CONFIRMED); proof.setChainId(11155111); proof.setRegistryAddress("registry");
        proof.setOnChainDocumentId("chain-id");
        when(proofs.findById("pdf")).thenReturn(Optional.of(proof));
        when(registry.readDocument(11155111, "registry", "chain-id")).thenReturn(new RegistryClient.ChainProof(HASH, Instant.now()));
    }
    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test void matchedPdfReturnsExactlyTheBytesThatWereCheckedAndPersistsSeparateResult() {
        var content = service.content("pdf");
        assertEquals("MATCH", content.integrity().status()); assertSame(PDF, content.bytes());
        assertEquals(HASH, content.integrity().documentHash());
        verify(storage, times(1)).downloadDirect("storage", 50L * 1024 * 1024);
        verify(documents).updateIntegrityResult(eq("pdf"), eq("MATCH"), any(), anyString());
        verify(proofs, never()).save(any());
    }
    @Test void missingAuthenticationCannotUseSystemFallbackOrReadFile() {
        UserContextHolder.clear();
        assertEquals(401, assertThrows(AppException.class, () -> service.check("pdf")).getStatusCode().value());
        verifyNoInteractions(contracts, storage, registry);
    }
    @Test void unauthorizedReaderCannotTouchStorageOrBlockchain() {
        when(contracts.getDocumentMetadata("pdf")).thenThrow(new AppException(ContractErrorCode.CONTRACT_FORBIDDEN));
        assertEquals(403, assertThrows(AppException.class, () -> service.content("pdf")).getStatusCode().value());
        verifyNoInteractions(storage, registry);
    }
    @Test void substitutedStorageBytesAreBlockedWithoutChangingConfirmedProof() {
        when(storage.downloadDirect("storage", 50L * 1024 * 1024)).thenReturn("substituted".getBytes(StandardCharsets.UTF_8));
        assertEquals("MISMATCH", service.check("pdf").status());
        assertEquals(6083, assertThrows(AppException.class, () -> service.content("pdf")).getCode());
        assertEquals(ProofStatus.CONFIRMED, proof.getStatus()); verifyNoInteractions(registry);
    }
    @Test void metadataVersionSubstitutionIsBlockedBeforeFileRead() {
        document.setSignedS3VersionId("different-version");
        assertEquals("MISMATCH", service.check("pdf").status());
        verify(storage, never()).downloadDirect(anyString(), anyLong());
    }
    @Test void proofFromAnotherRevisionCannotAuthenticateThisPdf() {
        proof.setRevisionId("other-revision");
        assertEquals("MISMATCH", service.check("pdf").status()); verifyNoInteractions(storage, registry);
    }
    @Test void inconsistentOnChainHashBlocksContent() {
        when(registry.readDocument(11155111, "registry", "chain-id")).thenReturn(new RegistryClient.ChainProof(OTHER, Instant.now()));
        assertEquals("MISMATCH", service.check("pdf").status());
        assertEquals(6083, assertThrows(AppException.class, () -> service.content("pdf")).getCode());
    }
    @Test void rpcOutageIsUnavailableAndNeverFileMismatch() {
        when(registry.readDocument(11155111, "registry", "chain-id")).thenThrow(new IllegalStateException("private RPC URL"));
        var result = service.check("pdf"); assertEquals("UNAVAILABLE", result.status());
        assertFalse(result.message().contains("private RPC"));
        assertEquals(6084, assertThrows(AppException.class, () -> service.content("pdf")).getCode());
    }
    @Test void missingS3VersionIsReportedAsMissing() {
        when(storage.downloadDirect("storage", 50L * 1024 * 1024)).thenThrow(new AppException(StorageErrorCode.STORAGE_OBJECT_NOT_FOUND));
        assertEquals("MISSING", service.check("pdf").status());
        assertEquals(6085, assertThrows(AppException.class, () -> service.content("pdf")).getCode());
    }
    @Test void fileBeyondBoundIsUnavailableAndCannotBeServed() {
        when(storage.downloadDirect("storage", 50L * 1024 * 1024)).thenThrow(new AppException(StorageErrorCode.STORAGE_FILE_TOO_LARGE));
        assertEquals("UNAVAILABLE", service.check("pdf").status());
        assertEquals(6084, assertThrows(AppException.class, () -> service.content("pdf")).getCode());
    }
    @Test void pendingAnchorAllowsLocallyMatchedBytesWithoutClaimingBlockchainMatch() {
        proof.setStatus(ProofStatus.SUBMITTED);
        assertEquals("PENDING", service.content("pdf").integrity().status()); verifyNoInteractions(registry);
    }
    @Test void disabledBlockchainStillChecksFinalHashForWorkflowOrRenewal() {
        when(proofs.findById("pdf")).thenReturn(Optional.empty());
        assertEquals("PENDING", service.content("pdf").integrity().status()); verifyNoInteractions(registry);
    }
    @Test void legacyWithoutBaselineRemainsReadableWithoutClaimingVerificationOrAutoPinning() {
        when(proofs.findById("pdf")).thenReturn(Optional.empty());
        document.setSignedSha256(null); document.setSignedS3VersionId(null);
        assertEquals("NOT_AVAILABLE", service.content("pdf").integrity().status());
        assertNull(document.getSignedS3VersionId()); verify(documents, never()).save(any()); verifyNoInteractions(registry);
    }
    @Test void nonFinalDocumentCannotEnterVerifiedPdfEndpoint() {
        document.setPurpose(DocumentPurpose.SIGNED_LANDLORD);
        assertEquals(400, assertThrows(AppException.class, () -> service.check("pdf")).getStatusCode().value());
        verifyNoInteractions(storage, registry);
    }
}
