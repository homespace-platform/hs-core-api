package com.hs.contract.notification;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.Contract;
import com.hs.contract.repository.ContractRepository;
import com.hs.contract.repository.RentalWorkflowRecordRepository;
import com.hs.contract.repository.WorkflowSigningDocumentRepository;

/** The agreement outbox joins the business transaction; a rollback cannot leave a signing notice behind. */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL", matches = "jdbc:postgresql://[^/]+/core_notification_rollout_test_[A-Za-z0-9]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.datasource.url=${HS_TEST_PG_URL}", "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.profiles.active=test"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AgreementSigningNotificationWriter.class, AmendmentNotificationWriter.class, RenewalNotificationWriter.class, TerminationNotificationWriter.class})
class AgreementSigningNotificationPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs.contract.notification")
    @EnableJpaRepositories(basePackageClasses = ContractNotificationOutboxRepository.class)
    static class Config {}

    @MockitoBean ContractRepository contracts;
    @MockitoBean WorkflowSigningDocumentRepository documents;
    @MockitoBean RentalWorkflowRecordRepository workflows;
    @MockitoBean MoveInHandoverNotificationWriter handover;
    @Autowired AgreementSigningNotificationWriter writer;
    @Autowired AmendmentNotificationWriter amendmentWriter;
    @Autowired RenewalNotificationWriter renewalWriter;
    @Autowired TerminationNotificationWriter terminationWriter;
    @Autowired ContractNotificationOutboxRepository outbox;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach void clearOutbox() { outbox.deleteAll(); }

    @Test void writesOnlyInsideTheCallerTransactionAndRollsBackWithIt() throws Exception {
        var lease = Contract.builder().id("lease").landlordId("landlord").tenantId("tenant").build();
        assertThrows(IllegalTransactionStateException.class, () -> writer.signingTurn(lease));
        new TransactionTemplate(manager).executeWithoutResult(tx -> writer.signingTurn(lease));
        assertEquals(1, outbox.count());
        var first = outbox.findAll().getFirst();
        var event = new ObjectMapper().readTree(first.getPayload());
        assertEquals(2, event.get("data").size());
        assertEquals("lease", event.get("data").get("agreementId").asText());
        assertEquals("LEASE", event.get("data").get("agreementKind").asText());
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            writer.signingTurn(lease);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(1, outbox.count());
        assertEquals(first.getEventId(), outbox.findAll().getFirst().getEventId());
    }

    @Test void amendmentNoticeIsAtomicWithTheCallerTransaction() {
        var root = Contract.builder().id("lease").landlordId("landlord").tenantId("tenant").build();
        assertThrows(IllegalTransactionStateException.class, () -> amendmentWriter.other(root, "landlord",
                AmendmentNotificationWriter.Event.AMENDMENT_RESPONSE_NEEDED));
        new TransactionTemplate(manager).executeWithoutResult(tx -> amendmentWriter.both(root,
                AmendmentNotificationWriter.Event.AMENDMENT_APPLIED));
        assertEquals(2, outbox.count());
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            amendmentWriter.other(root, "landlord", AmendmentNotificationWriter.Event.AMENDMENT_DECIDED);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(2, outbox.count());
    }

    @Test void renewalNoticeIsAtomicWithTheCallerTransaction() {
        var root = Contract.builder().id("lease").landlordId("landlord").tenantId("tenant").build();
        assertThrows(IllegalTransactionStateException.class, () -> renewalWriter.other(root, "tenant",
                RenewalNotificationWriter.Event.RENEWAL_REQUESTED));
        new TransactionTemplate(manager).executeWithoutResult(tx -> renewalWriter.other(root, "tenant",
                RenewalNotificationWriter.Event.RENEWAL_REQUESTED));
        assertEquals(1, outbox.count());
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            renewalWriter.both(root, RenewalNotificationWriter.Event.RENEWAL_EXPIRED);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(1, outbox.count());
    }

    @Test void terminationNoticeIsAtomicWithTheCallerTransaction() {
        var root = Contract.builder().id("lease").landlordId("landlord").tenantId("tenant").build();
        assertThrows(IllegalTransactionStateException.class, () -> terminationWriter.tenant(root,
                TerminationNotificationWriter.Event.TERMINATION_NOTICE_ISSUED));
        new TransactionTemplate(manager).executeWithoutResult(tx -> terminationWriter.both(root,
                TerminationNotificationWriter.Event.TERMINATION_ENDED));
        assertEquals(2, outbox.count());
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            terminationWriter.both(root, TerminationNotificationWriter.Event.LEASE_EXPIRED);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(2, outbox.count());
    }
}
