package com.hs.contract.blockchain.api;

import com.hs.contract.blockchain.persistence.*;
import com.hs.contract.config.AdminBlockchainIndexMigration;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.payment.membership.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="HS_P6_TEST_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_p6_test")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=AdminBlockchainPostgresTest.Config.class)
@TestPropertySource(properties={"spring.datasource.url=${HS_P6_TEST_PG_URL}","spring.datasource.username=postgres",
        "spring.datasource.password=p6_disposable","spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"})
@Import({AdminBlockchainQueries.class,AdminEthPaymentQueries.class,AdminBlockchainIndexMigration.class})
class AdminBlockchainPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={ContractRepository.class,ContractDocumentRepository.class,ContractDocumentProofRepository.class})
    static class Config {}
    @Autowired AdminBlockchainQueries queries;
    @Autowired AdminEthPaymentQueries eth;
    @Autowired AdminBlockchainIndexMigration migration;
    @Autowired ContractRepository contracts;
    @Autowired ContractDocumentRepository documents;
    @Autowired ContractDocumentProofRepository proofs;
    @Autowired JdbcTemplate jdbc;
    static final Instant NOW=Instant.parse("2026-11-28T00:00:00Z");
    static final String HASH="0x"+"a".repeat(64);
    String contract(String number) {
        var c=Contract.builder().id(UUID.randomUUID().toString()).contractNumber(number).rentalRequestId("request")
                .listingId("listing").landlordId("private-landlord").tenantId("private-tenant")
                .templateId("template").templateVersionId("template-version").build();
        return contracts.saveAndFlush(c).getId();
    }
    String proof(String contract,String status,Instant created,int attempts,String error) {
        String id=UUID.randomUUID().toString();
        var p=new ContractDocumentProof(); p.setDocumentId(id); p.setContractId(contract); p.setRevisionId("revision");
        p.setStorageObjectId("private-storage"); p.setOnChainDocumentId("0x"+id.replace("-","").repeat(2));
        p.setDocumentHash(HASH); p.setChainId(11155111); p.setRegistryAddress("0x"+"1".repeat(40));
        p.setStatus(ProofStatus.valueOf(status)); p.setCreatedAt(created); p.setAttempts(attempts); p.setErrorCode(error);
        p.setSignedRawTransaction("private-raw"); p.setErrorMessage("private-rpc-key"); p.setDocumentKind(2);
        proofs.saveAndFlush(p); return id;
    }
    @Test void emptyAggregatesAreZeroButMissingAgesAndLeaseAreNull() {
        var queue=queries.queue(NOW); assertEquals(4,queue.counts().size());
        assertTrue(queue.counts().values().stream().allMatch(c->c==0)); assertNull(queue.oldest());
        assertEquals(0,queue.stuck()); assertEquals(0,eth.unsettledCount()); assertNull(queries.senderLease());
        assertEquals(new AdminEthPaymentQueries.Counts(0,0,0),eth.counts());
        assertTrue(queries.proofs(null,null,null,null,PageRequest.of(0,20)).isEmpty());
    }
    @Test void queueUsesStrictRealTimeBoundaryAndTerminalStatesNeverRetry() {
        String c=contract("HD-Q");
        proof(c,"PENDING",NOW.minusSeconds(901),1,"RPC_UNAVAILABLE");
        proof(c,"SUBMITTED",NOW.minusSeconds(900),0,"private-rpc-key");
        proof(c,"CONFIRMED",NOW.minusSeconds(9999),3,null);
        proof(c,"FAILED",NOW.minusSeconds(9999),2,"unknown");
        var q=queries.queue(NOW);
        assertEquals(Map.of("PENDING",1L,"SUBMITTED",1L,"CONFIRMED",1L,"FAILED",1L),q.counts());
        assertEquals(1,q.stuck()); assertEquals(1,q.retrying()); assertEquals(NOW.minusSeconds(901),q.oldest());
        assertEquals(Map.of("RPC_UNAVAILABLE",1L,"OTHER",2L),q.errors());
    }
    @Test void joinedProjectionFiltersHalfOpenDatesEscapesWildcardsAndPaginatesTies() {
        String c=contract("HD_%literal"), other=contract("HD-else");
        String a=proof(c,"PENDING",NOW,0,null), b=proof(c,"PENDING",NOW,0,"secret");
        proof(c,"FAILED",NOW.plusSeconds(1),1,null); proof(other,"PENDING",NOW,0,null);
        var first=queries.proofs("PENDING","HD_%",NOW,NOW.plusSeconds(1),PageRequest.of(0,1));
        var second=queries.proofs("PENDING","HD_%",NOW,NOW.plusSeconds(1),PageRequest.of(1,1));
        assertEquals(2,first.getTotalElements()); assertNotEquals(first.getContent().getFirst().documentId(),second.getContent().getFirst().documentId());
        assertEquals(List.of(a,b).stream().sorted(Comparator.reverseOrder()).toList(),List.of(first.getContent().getFirst().documentId(),second.getContent().getFirst().documentId()));
        assertTrue(queries.proofs(null,"' OR 1=1 --",null,null,PageRequest.of(0,20)).isEmpty());
        var detail=queries.proof(b).orElseThrow(); assertEquals("OTHER",detail.errorCode()); assertEquals(HASH,detail.documentHash());
        assertFalse(detail.toString().contains("private")); assertTrue(queries.proof("missing").isEmpty());
    }
    @Test void legacyIntegrityAndOnlyOpenBlockchainIncidentsAndExactLease() {
        var d=ContractDocument.builder().contractId("legacy").revisionId("legacy").templateVersionId("template")
                .documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.SIGNED_FINAL).status(DocumentGenerationStatus.READY).build();
        documents.saveAndFlush(d);
        assertEquals(1,queries.integrityCounts().get("UNCHECKED"));
        jdbc.update("update contract_documents set integrity_status='MISMATCH' where id=?",d.getId());
        assertEquals(1,queries.integrityCounts().get("MISMATCH"));
        jdbc.update("insert into operational_incidents(id,source_key,component,open) values ('a','private:a','BLOCKCHAIN',true),('b','private:b','BLOCKCHAIN',false),('c','private:c','GATEWAY',true)");
        assertEquals(1,queries.openIncidents());
        jdbc.update("insert into blockchain_sender_leases(id,owner_token,expires_at) values ('contract-document-proof-sender','private-token',?),('other','other-token',?)",
                java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW.plusSeconds(100)));
        assertEquals(NOW,queries.senderLease());
    }
    void order(String id,String provider,String status,String hash) {
        jdbc.update("insert into membership_orders(id,owner_id,kind,tier,amount_vnd,upgrade,status,provider,created_at,expires_at) values (?, 'private-owner','PLAN','PRO',123,false,?,?,?,?)",
                id,status,provider,java.sql.Timestamp.from(NOW.minusSeconds(300)),java.sql.Timestamp.from(NOW.plusSeconds(300)));
        jdbc.update("insert into membership_eth_quotes(payment_id,membership_order_id,payer_wallet,recipient_wallet,contract_address,amount_vnd,eth_vnd,amount_wei,price_updated_at,expires_at,signature,transaction_hash) "
                + "values (?,?,'private-wallet','private-recipient','private-address',123.45,999,'123456789123456789',?,?,'private-signature',?)",
                "quote-"+id,id,java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW.plusSeconds(300)),hash);
    }
    @Test void ethReconciliationCountsOnlyRecordedTransactionsAndNeverLosesDecimalPrecision() {
        order("pending","ETH","PENDING",HASH); order("paid","ETH","PAID","0x"+"b".repeat(64));
        order("expired","ETH","EXPIRED","0x"+"c".repeat(64)); order("cancelled","ETH","CANCELLED","0x"+"d".repeat(64));
        order("no-tx","ETH","PENDING",null); order("legacy","SEPAY","PENDING","0x"+"e".repeat(64));
        assertEquals(3,eth.unsettledCount());
        assertEquals(new AdminEthPaymentQueries.Counts(4,1,3),eth.counts());
        var page=eth.payments(true,PageRequest.of(0,2),NOW); assertEquals(3,page.getTotalElements()); assertEquals(2,page.getNumberOfElements());
        assertTrue(page.stream().allMatch(p->!p.status().equals("PAID") && p.ageSeconds()==300));
        assertEquals("123456789123456789",page.getContent().getFirst().amountWei());
        assertEquals(0,new java.math.BigDecimal("123.45").compareTo(page.getContent().getFirst().amountVnd()));
        assertEquals(4,eth.payments(false,PageRequest.of(0,50),NOW).getTotalElements());
        assertFalse(page.toString().contains("private"));
    }
    @Test void paidEthRemainsVisibleWithoutAnyContractProofs() {
        order("paid-only","ETH","PAID",HASH);
        jdbc.update("update membership_eth_quotes set confirmed_at=? where membership_order_id='paid-only'",java.sql.Timestamp.from(NOW));
        assertTrue(queries.queue(NOW).counts().values().stream().allMatch(c->c==0));
        assertEquals(new AdminEthPaymentQueries.Counts(1,1,0),eth.counts());
        var all=eth.payments(false,PageRequest.of(0,20),NOW);
        assertEquals(1,all.getTotalElements());
        assertEquals("PAID",all.getContent().getFirst().status());
        assertEquals(HASH,all.getContent().getFirst().transactionHash());
        assertEquals(NOW,all.getContent().getFirst().confirmedAt());
        assertTrue(eth.payments(true,PageRequest.of(0,20),NOW).isEmpty());
    }
    @Test void migrationIsIdempotentOnCurrentSchema() {
        migration.migrate(); migration.migrate();
        assertEquals(2,jdbc.queryForObject("select count(*) from pg_indexes where indexname in ('idx_proof_status_created','idx_proof_created')",Integer.class));
    }
}
