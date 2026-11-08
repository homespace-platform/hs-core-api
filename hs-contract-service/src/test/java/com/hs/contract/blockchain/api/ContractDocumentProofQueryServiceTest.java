package com.hs.contract.blockchain.api;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.blockchain.client.BlockchainException;
import com.hs.contract.blockchain.client.RegistryClient;
import com.hs.contract.blockchain.persistence.ContractDocumentProof;
import com.hs.contract.blockchain.persistence.ContractDocumentProofRepository;
import com.hs.contract.blockchain.persistence.ProofStatus;
import com.hs.contract.dto.response.ContractDocumentResponse;
import com.hs.contract.model.constant.*;
import com.hs.contract.service.ContractService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractDocumentProofQueryServiceTest {
    private static final String HASH = "0x" + "a".repeat(64);
    private static final String OTHER = "0x" + "b".repeat(64);
    private static final String REGISTRY = "0x" + "2".repeat(40);
    private static final String CHAIN_ID = "0x" + "1".repeat(64);
    private final ContractService contracts = mock(ContractService.class);
    private final ContractDocumentProofRepository proofs = mock(ContractDocumentProofRepository.class);
    private final RegistryClient registry = mock(RegistryClient.class);
    private final ContractDocumentProofQueryService service = new ContractDocumentProofQueryService(contracts, proofs, registry);
    private ContractDocumentResponse document;
    private ContractDocumentProof proof;

    @BeforeEach void setup() {
        UserContextHolder.set(new UserContext("tenant", null));
        document = ContractDocumentResponse.builder().id("pdf").contractId("contract").revisionId("revision")
                .storageObjectId("storage").documentType(ContractDocumentType.PDF)
                .purpose(DocumentPurpose.SIGNED_FINAL).status(DocumentGenerationStatus.READY).build();
        proof = new ContractDocumentProof();
        proof.setDocumentId("pdf"); proof.setContractId("contract"); proof.setRevisionId("revision");
        proof.setStorageObjectId("storage"); proof.setDocumentHash(HASH); proof.setOnChainDocumentId(CHAIN_ID);
        proof.setChainId(11155111); proof.setRegistryAddress(REGISTRY); proof.setStatus(ProofStatus.CONFIRMED);
        when(contracts.getDocument("pdf")).thenReturn(document);
        when(proofs.findById("pdf")).thenReturn(Optional.of(proof));
    }

    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test void missingAuthenticationCannotUseTheLegacySystemFallback() {
        UserContextHolder.clear();
        var error = assertThrows(AppException.class, () -> service.get("pdf"));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verify(contracts, never()).getDocument(anyString()); verifyNoInteractions(registry);
    }

    @Test void forbiddenReaderCannotReadProofOrContactRpc() {
        when(contracts.getDocument("pdf")).thenThrow(new AppException(ContractErrorCode.CONTRACT_FORBIDDEN));
        var error = assertThrows(AppException.class, () -> service.verify("pdf", HASH));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(proofs, never()).findById(anyString()); verifyNoInteractions(registry);
    }

    @Test void oldSignedDocumentWithoutProofIsNotAnRpcFailure() {
        when(proofs.findById("pdf")).thenReturn(Optional.empty());
        assertEquals("NOT_AVAILABLE", service.get("pdf").status());
        verifyNoInteractions(registry);
        assertEquals(HttpStatus.CONFLICT, assertThrows(AppException.class, () -> service.verify("pdf", HASH)).getStatusCode());
    }

    @Test void statusReadNeverNeedsRpcOrSignerSecrets() {
        proof.setSignedRawTransaction("internal-raw-transaction"); proof.setNonce("10");
        var response = service.get("pdf");
        assertEquals("CONFIRMED", response.status()); assertEquals(HASH, response.documentHash());
        verifyNoInteractions(registry);
        assertTrue(java.util.Arrays.stream(BlockchainProofResponse.class.getRecordComponents())
                .noneMatch(field -> field.getName().matches("(?i).*password.*|.*privateKey.*|.*rawTransaction.*|nonce")));
    }

    @Test void proofOfAnotherRevisionCannotBeAttachedToThisPdf() {
        proof.setRevisionId("another-revision");
        assertEquals(6082, assertThrows(AppException.class, () -> service.get("pdf")).getCode());
        assertThrows(AppException.class, () -> service.verify("pdf", HASH)); verifyNoInteractions(registry);
    }

    @Test void nonFinalDocumentCannotBorrowAFinalProof() {
        document.setPurpose(DocumentPurpose.SIGNED_LANDLORD);
        assertEquals(6082, assertThrows(AppException.class, () -> service.get("pdf")).getCode());
    }

    @Test void verificationReadsFrozenChainAndRegistryAndMatchesIgnoringHexCase() {
        Instant registeredAt = Instant.parse("2026-11-08T12:00:00Z");
        when(registry.readDocument(11155111, REGISTRY, CHAIN_ID)).thenReturn(new RegistryClient.ChainProof(HASH, registeredAt));
        var response = service.verify("pdf", "0x" + "A".repeat(64));
        assertTrue(response.registered()); assertTrue(response.matches()); assertEquals(registeredAt, response.registeredAt());
        verify(registry).readDocument(11155111, REGISTRY, CHAIN_ID);
    }

    @Test void differentFileIsAMismatchNotAServerError() {
        when(registry.readDocument(11155111, REGISTRY, CHAIN_ID)).thenReturn(new RegistryClient.ChainProof(HASH, Instant.now()));
        var response = service.verify("pdf", OTHER);
        assertTrue(response.registered()); assertFalse(response.matches());
    }

    @Test void notRegisteredIsExplicitRatherThanSayingTheFileWasChanged() {
        when(registry.readDocument(11155111, REGISTRY, CHAIN_ID))
                .thenReturn(new RegistryClient.ChainProof("0x" + "0".repeat(64), null));
        var response = service.verify("pdf", HASH);
        assertFalse(response.registered()); assertFalse(response.matches());
    }

    @Test void conflictingOnChainCommitmentRequiresReconciliation() {
        when(registry.readDocument(11155111, REGISTRY, CHAIN_ID)).thenReturn(new RegistryClient.ChainProof(OTHER, Instant.now()));
        assertEquals(6082, assertThrows(AppException.class, () -> service.verify("pdf", OTHER)).getCode());
    }

    @Test void rpcFailureDoesNotBecomeFalseOrExposeRpcCredentials() {
        when(registry.readDocument(11155111, REGISTRY, CHAIN_ID))
                .thenThrow(new BlockchainException("RPC_UNAVAILABLE", "Không thể kết nối RPC"));
        var error = assertThrows(AppException.class, () -> service.verify("pdf", HASH));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertEquals(6081, error.getCode());
    }

    @Test void rejectsMalformedHashBeforeCallingRpc() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(AppException.class, () -> service.verify("pdf", "pdf-url")).getStatusCode());
        verifyNoInteractions(registry);
    }

    @Test void sdkInitializationFailureCannotReachTheGlobalLoggerWithTheRpcUrl() {
        when(registry.readDocument(11155111, REGISTRY, CHAIN_ID))
                .thenThrow(new IllegalArgumentException("Invalid RPC URL https://example.invalid/private-api-key"));
        var error = assertThrows(AppException.class, () -> service.verify("pdf", HASH));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertFalse(error.toString().contains("private-api-key")); assertNull(error.getCause());
    }
}
