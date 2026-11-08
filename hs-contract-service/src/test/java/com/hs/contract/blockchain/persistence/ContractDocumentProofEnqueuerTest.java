package com.hs.contract.blockchain.persistence;

import com.hs.contract.blockchain.config.BlockchainProperties;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.constant.ContractDocumentType;
import com.hs.contract.model.constant.DocumentGenerationStatus;
import com.hs.contract.model.constant.DocumentPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractDocumentProofEnqueuerTest {
    @Mock ContractDocumentProofRepository proofs;
    @Mock BlockchainProperties properties;
    ContractDocumentProofEnqueuer enqueuer;
    private static final String HASH = "0x" + "a".repeat(64);
    private static final String REGISTRY = "0x" + "1".repeat(40);

    @BeforeEach
    void setup() {
        enqueuer = new ContractDocumentProofEnqueuer(proofs, properties);
        lenient().when(properties.isEnabled()).thenReturn(true);
        lenient().when(properties.isConfigured()).thenReturn(true);
        lenient().when(properties.getChainId()).thenReturn(11155111L);
        lenient().when(properties.getRegistryAddress()).thenReturn(REGISTRY);
    }

    @Test
    void hashesExactBytesWithSha256RatherThanSnapshotOrKeccak() {
        assertEquals("0xba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DocumentProofHash.sha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(DocumentProofHash.sha256("abc".getBytes(StandardCharsets.UTF_8)),
                DocumentProofHash.sha256("abc\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void depositAndBothRootLeaseRepresentationsReceiveSeparateOpaqueProofs() {
        String previousId = null;
        for (String kind : new String[] {null, "LEASE", "DEPOSIT"}) {
            Contract c = contract(kind);
            enqueuer.enqueue(c, document(), HASH);
            var argument = ArgumentCaptor.forClass(ContractDocumentProof.class);
            verify(proofs).save(argument.capture());
            ContractDocumentProof saved = argument.getValue();
            assertEquals("pdf-final", saved.getDocumentId());
            assertEquals("contract", saved.getContractId());
            assertEquals("revision", saved.getRevisionId());
            assertEquals("storage-final", saved.getStorageObjectId());
            assertEquals(HASH, saved.getDocumentHash());
            assertEquals(11155111L, saved.getChainId());
            assertEquals(REGISTRY, saved.getRegistryAddress());
            assertEquals(ProofStatus.PENDING, saved.getStatus());
            assertTrue(saved.getOnChainDocumentId().matches("0x[0-9a-f]{64}"));
            assertNotEquals(previousId, saved.getOnChainDocumentId());
            assertNotNull(saved.getNextAttemptAt());
            assertNull(saved.getTransactionHash());
            previousId = saved.getOnChainDocumentId();
            clearInvocations(proofs);
        }
    }

    @Test
    void disabledOrIncompletePublicConfigurationDoesNotAffectSigning() {
        when(properties.isEnabled()).thenReturn(false);
        enqueuer.enqueue(contract(null), document(), HASH);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.isConfigured()).thenReturn(false);
        enqueuer.enqueue(contract("DEPOSIT"), document(), HASH);
        verifyNoInteractions(proofs);
    }

    @Test
    void firstSignatureAndDraftArtifactsDoNotProduceProofs() {
        for (DocumentPurpose purpose : new DocumentPurpose[] {
                DocumentPurpose.PREVIEW, DocumentPurpose.OFFICIAL, DocumentPurpose.SIGNED_LANDLORD}) {
            ContractDocument doc = document();
            doc.setPurpose(purpose);
            enqueuer.enqueue(contract(null), doc, HASH);
        }
        ContractDocument doc = document();
        doc.setDocumentType(ContractDocumentType.DOCX);
        enqueuer.enqueue(contract(null), doc, HASH);
        doc = document();
        doc.setStatus(DocumentGenerationStatus.GENERATING);
        enqueuer.enqueue(contract(null), doc, HASH);
        verifyNoInteractions(proofs);
    }

    @Test
    void renewalAmendmentAndWorkflowDocumentsAreOutsideTheInitialScope() {
        Contract renewal = contract(null);
        renewal.setParentContractId("parent-lease");
        enqueuer.enqueue(renewal, document(), HASH);
        enqueuer.enqueue(contract("AMENDMENT"), document(), HASH);
        enqueuer.enqueue(contract("WORKFLOW"), document(), HASH);
        verifyNoInteractions(proofs);
    }

    @Test
    void documentMustBelongToTheSameContractAndRevisionAndHaveStoredBytes() {
        ContractDocument doc = document();
        doc.setContractId("another-contract");
        enqueuer.enqueue(contract(null), doc, HASH);
        doc = document();
        doc.setRevisionId("old-revision");
        enqueuer.enqueue(contract(null), doc, HASH);
        doc = document();
        doc.setStorageObjectId(null);
        enqueuer.enqueue(contract(null), doc, HASH);
        verifyNoInteractions(proofs);
    }

    @Test
    void retryReusesThePersistedIdentityAndOriginalRegistry() {
        BlockchainProperties actualProperties = new BlockchainProperties();
        actualProperties.setEnabled(true);
        actualProperties.setRpcUrl("http://127.0.0.1:8545");
        actualProperties.setRegistryAddress(REGISTRY);
        enqueuer = new ContractDocumentProofEnqueuer(proofs, actualProperties);
        Map<String, ContractDocumentProof> persisted = new HashMap<>();
        when(proofs.findById("pdf-final")).thenAnswer(call -> Optional.ofNullable(persisted.get("pdf-final")));
        when(proofs.save(any())).thenAnswer(call -> {
            ContractDocumentProof proof = call.getArgument(0);
            persisted.put(proof.getDocumentId(), proof);
            return proof;
        });
        enqueuer.enqueue(contract("DEPOSIT"), document(), HASH);
        String opaqueId = persisted.get("pdf-final").getOnChainDocumentId();
        actualProperties.setRegistryAddress("0x" + "2".repeat(40));
        enqueuer.enqueue(contract("DEPOSIT"), document(), HASH.toUpperCase().replace("0X", "0x"));
        verify(proofs, times(1)).save(any());
        assertEquals(opaqueId, persisted.get("pdf-final").getOnChainDocumentId());
        assertEquals(REGISTRY, persisted.get("pdf-final").getRegistryAddress());
    }

    @Test
    void sameDocumentCannotBeRequeuedWithDifferentBytes() {
        ContractDocumentProof saved = new ContractDocumentProof();
        saved.setDocumentId("pdf-final");
        saved.setContractId("contract");
        saved.setRevisionId("revision");
        saved.setStorageObjectId("storage-final");
        saved.setDocumentHash(HASH);
        when(proofs.findById("pdf-final")).thenReturn(Optional.of(saved));
        assertThrows(IllegalStateException.class, () ->
                enqueuer.enqueue(contract(null), document(), "0x" + "b".repeat(64)));
        verify(proofs, never()).save(any());
    }

    @Test
    void missingMalformedOrZeroHashesNeverCreateAJob() {
        for (String hash : new String[] {null, "snapshot-hash", "0x1234", "0x" + "0".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> enqueuer.enqueue(contract(null), document(), hash));
        }
        verifyNoInteractions(proofs);
    }

    private static Contract contract(String kind) {
        return Contract.builder().id("contract").agreementKind(kind).currentRevisionId("revision").build();
    }

    private static ContractDocument document() {
        return ContractDocument.builder().id("pdf-final").contractId("contract").revisionId("revision")
                .storageObjectId("storage-final").documentType(ContractDocumentType.PDF)
                .purpose(DocumentPurpose.SIGNED_FINAL).status(DocumentGenerationStatus.READY).build();
    }
}
