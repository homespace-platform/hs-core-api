package com.hs.contract.blockchain.persistence;

import com.hs.contract.blockchain.config.BlockchainProperties;
import com.hs.contract.blockchain.worker.BlockchainSenderLeaseStore;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.constant.ContractDocumentType;
import com.hs.contract.model.constant.DocumentGenerationStatus;
import com.hs.contract.model.constant.DocumentPurpose;
import com.hs.contract.repository.ContractDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Chỉ tạo/xóa schema trong database thử nghiệm riêng được chỉ định tường minh. */
@EnabledIfEnvironmentVariable(named = "HS_BLOCKCHAIN_TEST_PG_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_blockchain_test")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ContractDocumentProofPostgresTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_BLOCKCHAIN_TEST_PG_URL}",
        "spring.datasource.username=${HS_BLOCKCHAIN_TEST_PG_USER:postgres}",
        "spring.datasource.password=${HS_BLOCKCHAIN_TEST_PG_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false"
})
@Import({ContractDocumentProofEnqueuer.class, BlockchainSenderLeaseStore.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContractDocumentProofPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses = {ContractDocumentProofRepository.class, ContractDocumentRepository.class})
    static class Config {
        @Bean
        BlockchainProperties blockchainProperties() {
            BlockchainProperties properties = new BlockchainProperties();
            properties.setEnabled(true);
            properties.setRpcUrl("http://127.0.0.1:8545");
            properties.setChainId(31337);
            properties.setRegistryAddress("0x" + "1".repeat(40));
            return properties;
        }
    }

    @Autowired ContractDocumentProofRepository proofs;
    @Autowired ContractDocumentRepository documents;
    @Autowired ContractDocumentProofEnqueuer enqueuer;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired BlockchainSenderLeaseStore leases;

    @Test
    void pdfAndProofRollbackTogetherAndCommittedProofIsDueOnlyOnce() {
        String rolledBackId = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> tx().executeWithoutResult(transaction -> {
            ContractDocument document = documents.saveAndFlush(document(rolledBackId));
            enqueuer.enqueue(contract(), document, "0x" + "a".repeat(64));
            proofs.flush();
            throw new IllegalStateException("Simulated finalization failure after enqueue");
        }));
        assertFalse(documents.existsById(rolledBackId));
        assertFalse(proofs.existsById(rolledBackId));

        String committedId = UUID.randomUUID().toString();
        tx().executeWithoutResult(transaction -> {
            ContractDocument document = documents.saveAndFlush(document(committedId));
            enqueuer.enqueue(contract(), document, "0x" + "a".repeat(64));
            enqueuer.enqueue(contract(), document, "0x" + "a".repeat(64));
        });
        assertTrue(documents.existsById(committedId));
        assertEquals(1, proofs.findDue(Instant.now().plusSeconds(1), PageRequest.of(0, 100)).stream()
                .filter(proof -> committedId.equals(proof.getDocumentId())).count());
    }

    @Test
    void databaseRejectsDuplicateDocumentIdentityAndDuplicateWalletNonce() {
        ContractDocumentProof first = proof("0x" + "b".repeat(64));
        tx().executeWithoutResult(transaction -> proofs.saveAndFlush(first));
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(transaction ->
                jdbc.update("insert into contract_document_proofs select * from contract_document_proofs where document_id = ?",
                        first.getDocumentId())));

        ContractDocumentProof second = proof("0x" + "c".repeat(64));
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(transaction ->
                proofs.saveAndFlush(second)));
        assertTrue(proofs.existsById(first.getDocumentId()));
        assertFalse(proofs.existsById(second.getDocumentId()));
    }

    @Test
    void enqueueRequiresTheExistingFinalizationTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () ->
                enqueuer.enqueue(contract(), document(UUID.randomUUID().toString()), "0x" + "a".repeat(64)));
    }

    @Test
    void leaseTakeoverFencesTheExpiredWorkerAndItsReleaseCannotDeleteTheNewOwner() {
        String leaseId = "sender-" + UUID.randomUUID();
        String firstToken = UUID.randomUUID().toString();
        String secondToken = UUID.randomUUID().toString();
        assertTrue(leases.acquire(leaseId, firstToken));
        assertFalse(leases.acquire(leaseId, secondToken));
        assertEquals(Boolean.TRUE, tx().execute(transaction -> leases.ownsForUpdate(leaseId, firstToken)));
        assertEquals(Boolean.FALSE, tx().execute(transaction -> leases.ownsForUpdate(leaseId, secondToken)));

        jdbc.update("update blockchain_sender_leases set expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' where id = ?",
                leaseId);
        assertTrue(leases.acquire(leaseId, secondToken));
        assertEquals(Boolean.FALSE, tx().execute(transaction -> leases.ownsForUpdate(leaseId, firstToken)));
        assertEquals(Boolean.TRUE, tx().execute(transaction -> leases.ownsForUpdate(leaseId, secondToken)));

        leases.release(leaseId, firstToken);
        assertEquals(secondToken, jdbc.queryForObject(
                "select owner_token from blockchain_sender_leases where id = ?", String.class, leaseId));
        leases.release(leaseId, secondToken);
        assertEquals(0, jdbc.queryForObject("select count(*) from blockchain_sender_leases where id = ?", Integer.class, leaseId));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    private ContractDocumentProof proof(String onChainId) {
        ContractDocumentProof proof = new ContractDocumentProof();
        proof.setDocumentId(UUID.randomUUID().toString());
        proof.setContractId("contract");
        proof.setRevisionId("revision");
        proof.setStorageObjectId("storage");
        proof.setDocumentHash("0x" + "a".repeat(64));
        proof.setOnChainDocumentId(onChainId);
        proof.setChainId(31337);
        proof.setRegistryAddress("0x" + "1".repeat(40));
        proof.setSignerAddress("0x" + "2".repeat(40));
        proof.setNonce("17");
        return proof;
    }

    private Contract contract() {
        return Contract.builder().id("contract").agreementKind("DEPOSIT").currentRevisionId("revision").build();
    }

    private ContractDocument document(String id) {
        return ContractDocument.builder().id(id).contractId("contract").revisionId("revision")
                .templateVersionId("template").storageObjectId("storage").documentType(ContractDocumentType.PDF)
                .purpose(DocumentPurpose.SIGNED_FINAL).status(DocumentGenerationStatus.READY).build();
    }
}
