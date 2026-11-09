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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractDocumentProofWorkerTest {
    private static final String ADDRESS = "0x" + "1".repeat(40);
    private static final String RECORDER = "0x" + "2".repeat(40);
    private static final String ID = "0x" + "3".repeat(64);
    private static final String TX = "0x" + "4".repeat(64);
    private static final byte[] PDF = "%PDF-final-signed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @Mock ContractDocumentProofRepository proofs;
    @Mock RegistryClient registry;
    @Mock StorageService storage;
    @Mock BlockchainSenderLeaseStore leases;
    @Mock PlatformTransactionManager transactions;
    BlockchainProperties properties;
    ContractDocumentProofWorker worker;
    ContractDocumentProof proof;

    @BeforeEach void setup() {
        properties = new BlockchainProperties();
        properties.setEnabled(true);
        properties.setRpcUrl("http://127.0.0.1:8545");
        properties.setChainId(31337);
        properties.setRegistryAddress(ADDRESS);
        worker = new ContractDocumentProofWorker(properties, proofs, registry, storage, leases, transactions);
        proof = new ContractDocumentProof();
        proof.setDocumentId("document");
        proof.setStorageObjectId("storage");
        proof.setChainId(31337);
        proof.setRegistryAddress(ADDRESS);
        proof.setOnChainDocumentId(ID);
        proof.setDocumentHash(DocumentProofHash.sha256(PDF));
        proof.setCaseId("0x" + "5".repeat(64));
        proof.setDocumentKind(2);
        proof.setNextAttemptAt(Instant.EPOCH);
    }

    private void permitUpdates() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(leases.ownsForUpdate("lease", "token")).thenReturn(true);
        when(proofs.findByIdForUpdate("document")).thenReturn(Optional.of(proof));
    }

    private void absentOnChain() {
        when(registry.readDocument(31337, ADDRESS, ID)).thenReturn(new RegistryClient.ChainProof("0x" + "0".repeat(64), Instant.EPOCH));
    }

    private void persistedSubmission() {
        proof.setSignerAddress(RECORDER);
        proof.setNonce("7");
        proof.setSignedRawTransaction("0xraw-signed");
        proof.setTransactionHash(TX);
        proof.setStatus(ProofStatus.SUBMITTED);
    }


    @Test void savesSignedTransactionBeforeBroadcastAndDoesNotChangeFrozenIdentity() {
        permitUpdates(); absentOnChain();
        when(storage.downloadDirect("storage", 50L * 1024 * 1024)).thenReturn(PDF);
        var signed = new RegistryClient.SignedSubmission(RECORDER, "7", "0xraw-signed", TX);
        when(registry.prepare(31337, ADDRESS, ID, proof.getDocumentHash(), proof.getCaseId(), 2)).thenReturn(signed);
        doAnswer(call -> {
            assertEquals(TX, proof.getTransactionHash());
            assertEquals("0xraw-signed", proof.getSignedRawTransaction());
            assertEquals("7", proof.getNonce());
            assertEquals(ProofStatus.SUBMITTED, proof.getStatus());
            verify(proofs, atLeastOnce()).saveAndFlush(proof);
            return null;
        }).when(registry).broadcast(31337, ADDRESS, signed);
        worker.process(proof, "lease", "token");
        verify(registry).broadcast(31337, ADDRESS, signed);
        assertEquals(ID, proof.getOnChainDocumentId());
        assertEquals(DocumentProofHash.sha256(PDF), proof.getDocumentHash());
    }

    @Test void timeoutAfterBroadcastRetainsExactTransactionAndRestartNeverAllocatesAnotherNonce() {
        permitUpdates(); absentOnChain();
        when(storage.downloadDirect("storage", 50L * 1024 * 1024)).thenReturn(PDF);
        var signed = new RegistryClient.SignedSubmission(RECORDER, "7", "0xraw-signed", TX);
        when(registry.prepare(31337, ADDRESS, ID, proof.getDocumentHash(), proof.getCaseId(), 2)).thenReturn(signed);
        doThrow(new BlockchainException("RPC_UNAVAILABLE", "Unavailable")).when(registry).broadcast(31337, ADDRESS, signed);
        worker.process(proof, "lease", "token");
        assertEquals(ProofStatus.SUBMITTED, proof.getStatus());
        assertEquals("RPC_UNAVAILABLE", proof.getErrorCode());
        when(registry.receipt(31337, ADDRESS, ID, proof.getDocumentHash(), RECORDER, TX)).thenReturn(Optional.empty());
        worker.process(proof, "lease", "token");
        verify(registry, times(1)).prepare(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt());
        verify(registry, times(2)).broadcast(31337, ADDRESS, signed);
        assertEquals("7", proof.getNonce());
        assertEquals(TX, proof.getTransactionHash());
    }

    @Test void sameHashAlreadyOnChainIsReconciledWithoutSendingAgain() {
        permitUpdates();
        when(registry.readDocument(31337, ADDRESS, ID)).thenReturn(new RegistryClient.ChainProof(proof.getDocumentHash(), Instant.EPOCH));
        when(registry.findRegistration(31337, ADDRESS, ID, proof.getDocumentHash()))
                .thenReturn(Optional.of(new RegistryClient.Registration(TX, RECORDER)));
        when(registry.receipt(31337, ADDRESS, ID, proof.getDocumentHash(), RECORDER, TX))
                .thenReturn(Optional.of(new RegistryClient.Receipt(42, "0xblock", Instant.EPOCH, true)));
        when(registry.readLineage(31337, ADDRESS, ID))
                .thenReturn(new RegistryClient.ChainLineage(proof.getCaseId(), 2));
        worker.process(proof, "lease", "token");
        assertEquals(ProofStatus.CONFIRMED, proof.getStatus());
        assertEquals(42L, proof.getBlockNumber());
        assertNotNull(proof.getConfirmedAt());
        assertNotNull(proof.getNextAttemptAt());
        verify(registry, never()).prepare(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt());
        verify(registry, never()).broadcast(anyLong(), anyString(), any());
        verifyNoInteractions(storage);
    }

    @Test void mismatchedOnChainHashStopsWithoutHidingConflictWithNewId() {
        permitUpdates();
        when(registry.readDocument(31337, ADDRESS, ID)).thenReturn(new RegistryClient.ChainProof("0x" + "f".repeat(64), Instant.EPOCH));
        worker.process(proof, "lease", "token");
        assertEquals(ProofStatus.FAILED, proof.getStatus());
        assertEquals("DOCUMENT_HASH_CONFLICT", proof.getErrorCode());
        assertEquals(ID, proof.getOnChainDocumentId());
        assertNotNull(proof.getNextAttemptAt());
        verify(registry, never()).prepare(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt());
        verifyNoInteractions(storage);
    }

    @Test void changedStoragePdfCannotBeAnchored() {
        permitUpdates(); absentOnChain();
        when(storage.downloadDirect("storage", 50L * 1024 * 1024)).thenReturn("modified".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        worker.process(proof, "lease", "token");
        assertEquals("SOURCE_PDF_CHANGED", proof.getErrorCode());
        assertEquals(ProofStatus.FAILED, proof.getStatus());
        verify(registry, never()).prepare(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test void minedReceiptMustWaitForFinality() {
        permitUpdates(); persistedSubmission(); absentOnChain();
        when(registry.receipt(31337, ADDRESS, ID, proof.getDocumentHash(), RECORDER, TX))
                .thenReturn(Optional.of(new RegistryClient.Receipt(42, "0xblock", Instant.EPOCH, false)));
        worker.process(proof, "lease", "token");
        assertEquals(ProofStatus.SUBMITTED, proof.getStatus());
        assertNull(proof.getConfirmedAt());
        assertEquals("0xblock", proof.getBlockHash());
        verify(registry, never()).broadcast(anyLong(), anyString(), any());
    }

    @Test void finalizedReceiptWithWrongCaseDoesNotConfirmProof() {
        permitUpdates(); persistedSubmission(); absentOnChain();
        when(registry.receipt(31337, ADDRESS, ID, proof.getDocumentHash(), RECORDER, TX))
                .thenReturn(Optional.of(new RegistryClient.Receipt(42, "0xblock", Instant.EPOCH, true)));
        when(registry.readLineage(31337, ADDRESS, ID))
                .thenReturn(new RegistryClient.ChainLineage("0x" + "f".repeat(64), 2));
        worker.process(proof, "lease", "token");
        assertEquals(ProofStatus.FAILED, proof.getStatus());
        assertEquals("DOCUMENT_LINEAGE_CONFLICT", proof.getErrorCode());
    }

    @Test void invalidReceiptIsTerminalWithoutTouchingContractOrSignatureServices() {
        permitUpdates(); persistedSubmission(); absentOnChain();
        when(registry.receipt(31337, ADDRESS, ID, proof.getDocumentHash(), RECORDER, TX))
                .thenThrow(new BlockchainException("INVALID_RECEIPT", "Receipt invalid", true));
        worker.process(proof, "lease", "token");
        assertEquals(ProofStatus.FAILED, proof.getStatus());
        assertEquals("INVALID_RECEIPT", proof.getErrorCode());
        verify(registry, never()).broadcast(anyLong(), anyString(), any());
    }

    @Test void missingPrivateKeyIsVisibleOnQueuedProofAndRemainsRetryable() {
        permitUpdates(); absentOnChain();
        when(storage.downloadDirect("storage", 50L * 1024 * 1024)).thenReturn(PDF);
        when(registry.prepare(31337, ADDRESS, ID, proof.getDocumentHash(), proof.getCaseId(), 2))
                .thenThrow(new BlockchainException("PRIVATE_KEY_NOT_CONFIGURED", "Private key not configured"));
        worker.process(proof, "lease", "token");
        assertEquals(ProofStatus.PENDING, proof.getStatus());
        assertEquals("PRIVATE_KEY_NOT_CONFIGURED", proof.getErrorCode());
        assertNotNull(proof.getNextAttemptAt());
        verify(registry, never()).broadcast(anyLong(), anyString(), any());
    }

    @Test void expiredLeaseCannotMutateOrSend() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(leases.ownsForUpdate("lease", "token")).thenReturn(false);
        worker.process(proof, "lease", "token");
        verifyNoInteractions(registry, storage);
        verify(proofs, never()).saveAndFlush(any());
    }

    @Test void leaseLostAfterDurableSaveDoesNotBroadcastAndKeepsTransactionForNextWorker() {
        permitUpdates(); absentOnChain();
        when(leases.ownsForUpdate("lease", "token")).thenReturn(true, true, false);
        when(storage.downloadDirect("storage", 50L * 1024 * 1024)).thenReturn(PDF);
        when(registry.prepare(31337, ADDRESS, ID, proof.getDocumentHash(), proof.getCaseId(), 2))
                .thenReturn(new RegistryClient.SignedSubmission(RECORDER, "7", "0xraw-signed", TX));
        worker.process(proof, "lease", "token");
        assertEquals(TX, proof.getTransactionHash());
        assertEquals(ProofStatus.SUBMITTED, proof.getStatus());
        verify(registry, never()).broadcast(anyLong(), anyString(), any());
    }

    @Test void disabledConfigurationDoesNotReadWalletOrRpc() {
        properties.setEnabled(false);
        worker.tick();
        verifyNoInteractions(proofs, registry, storage, leases, transactions);
    }

    @Test void anotherInstanceHoldingLeasePreventsQueueProcessing() {
        when(leases.acquire(anyString(), anyString())).thenReturn(false);
        worker.tick();
        verifyNoInteractions(proofs, registry, storage);
    }

    @Test void submittedTransactionBlocksNewNonceEvenBeforeItsNextPoll() {
        persistedSubmission(); proof.setNextAttemptAt(Instant.now().plusSeconds(3600));
        when(leases.acquire(anyString(), anyString())).thenReturn(true);
        when(proofs.findByStatusOrderByCreatedAtAsc(ProofStatus.SUBMITTED)).thenReturn(List.of(proof));
        worker.tick();
        verify(proofs, never()).findDue(any(), any());
        verifyNoInteractions(registry, storage);
    }

    @Test void minedReceiptAllowsNextProofToLeaveQueueBeforeFinality() {
        persistedSubmission(); proof.setBlockNumber(42L);
        proof.setNextAttemptAt(Instant.now().plusSeconds(3600));
        when(leases.acquire(anyString(), anyString())).thenReturn(true);
        when(proofs.findByStatusOrderByCreatedAtAsc(ProofStatus.SUBMITTED)).thenReturn(List.of(proof));
        worker.tick();
        verify(proofs).findDue(any(), any());
    }

    @Test void minedReceiptWithRpcErrorStillBlocksNextNonce() {
        persistedSubmission(); proof.setBlockNumber(42L); proof.setErrorCode("RPC_UNAVAILABLE");
        proof.setNextAttemptAt(Instant.now().plusSeconds(3600));
        when(leases.acquire(anyString(), anyString())).thenReturn(true);
        when(proofs.findByStatusOrderByCreatedAtAsc(ProofStatus.SUBMITTED)).thenReturn(List.of(proof));
        worker.tick();
        verify(proofs, never()).findDue(any(), any());
    }

    @Test void disappearedReceiptClearsMinedMarkerBeforeAnotherNonce() {
        permitUpdates(); persistedSubmission(); absentOnChain();
        proof.setBlockNumber(42L); proof.setBlockHash("0xblock"); proof.setRegisteredAt(Instant.EPOCH);
        when(registry.receipt(31337, ADDRESS, ID, proof.getDocumentHash(), RECORDER, TX)).thenReturn(Optional.empty());
        worker.process(proof, "lease", "token");
        assertNull(proof.getBlockNumber());
        assertNull(proof.getBlockHash());
        assertNull(proof.getRegisteredAt());
        assertEquals(ProofStatus.SUBMITTED, proof.getStatus());
        verify(registry, never()).prepare(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test void olderMinedReceiptIsRecheckedBeforeYoungerUnminedTransaction() {
        persistedSubmission(); proof.setBlockNumber(42L); proof.setNextAttemptAt(Instant.EPOCH);
        var later = new ContractDocumentProof();
        later.setDocumentId("later"); later.setStatus(ProofStatus.SUBMITTED);
        later.setNextAttemptAt(Instant.EPOCH);
        when(leases.acquire(anyString(), anyString())).thenReturn(true);
        when(leases.ownsForUpdate(anyString(), anyString())).thenReturn(true);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(proofs.findByIdForUpdate("document")).thenReturn(Optional.of(proof));
        when(proofs.findByStatusOrderByCreatedAtAsc(ProofStatus.SUBMITTED)).thenReturn(List.of(proof, later));
        absentOnChain();
        when(registry.receipt(31337, ADDRESS, ID, proof.getDocumentHash(), RECORDER, TX))
                .thenReturn(Optional.of(new RegistryClient.Receipt(42, "0xblock", Instant.EPOCH, false)));
        worker.tick();
        verify(registry).receipt(31337, ADDRESS, ID, proof.getDocumentHash(), RECORDER, TX);
        verify(proofs, never()).findByIdForUpdate("later");
    }
}
