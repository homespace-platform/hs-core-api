package com.hs.contract.deposit;

import com.hs.contract.config.AgreementTemplateMigration;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.listing.model.constant.ListingCategory;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: Hibernate creates/drops the schema in this explicitly named disposable local database. */
@EnabledIfEnvironmentVariable(named="HS_CONTRACT_TEST_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_contract_test")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=DepositRepositoryPostgresTest.Config.class)
@TestPropertySource(properties={"spring.datasource.url=${HS_CONTRACT_TEST_PG_URL}",
    "spring.datasource.username=postgres","spring.datasource.password=contract-test-only",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false"})
class DepositRepositoryPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={ContractRepository.class,DepositAgreementRepository.class})
    static class Config {}
    @Autowired ContractRepository contracts;
    @Autowired ContractTemplateRepository templates;
    @Autowired ContractTemplateVersionRepository versions;
    @Autowired DepositAgreementRepository deposits;
    @Autowired JdbcTemplate jdbc;

    @Test void migratesOldTemplateConstraintAndKeepsLeaseQueriesSeparateFromDepositEnvelopes() {
        // Simulate the previous unique-category constraint before introducing a deposit template.
        jdbc.execute("alter table contract_templates drop constraint if exists uq_contract_template_kind_category");
        jdbc.execute("alter table contract_templates add constraint uq_contract_template_category unique(category)");
        var migration=new AgreementTemplateMigration(jdbc);migration.migrate();migration.migrate();
        var lease=template("LEASE");var deposit=template("DEPOSIT");
        var leaseVersion=version(lease);var depositVersion=version(deposit);
        assertEquals(lease.getId(),templates.findSystemTemplatesByCategory(ListingCategory.HOUSE).getFirst().getId());
        assertEquals(deposit.getId(),templates.findSystemTemplatesByCategoryAndKind(ListingCategory.HOUSE,"DEPOSIT").getFirst().getId());
        assertEquals(List.of(leaseVersion.getId()),versions.findCurrentPublishedSystemVersions(ListingCategory.HOUSE).stream().map(ContractTemplateVersion::getId).toList());
        assertEquals(List.of(depositVersion.getId()),versions.findPublishedDepositVersions(ListingCategory.HOUSE).stream().map(ContractTemplateVersion::getId).toList());
        var leaseEnvelope=contracts.saveAndFlush(envelope("HD-TEST",null,leaseVersion));
        var depositEnvelope=contracts.saveAndFlush(envelope("DC-TEST","DEPOSIT",depositVersion));
        var depositData=new DepositAgreement();depositData.setRentalRequestId("rental");depositData.setSourcePaymentId("payment");
        depositData.setSigningContractId(depositEnvelope.getId());depositData.setSnapshotJson("{}");depositData.setSnapshotHash("hash");depositData.setCapturedAt(java.time.Instant.now());
        deposits.saveAndFlush(depositData);
        assertEquals(depositEnvelope.getId(),deposits.findByRentalRequestId("rental").orElseThrow().getSigningContractId());
        assertEquals(leaseEnvelope.getId(),contracts.findByRentalRequestId("rental").orElseThrow().getId());
        assertEquals(List.of(leaseEnvelope.getId()),contracts.findLifecycleIdsAfter(Set.of(ContractStatus.ACTIVE),null,PageRequest.of(0,100)));
        assertEquals(1,contracts.findByParentContractIdIsNullAndStatusIn(Set.of(ContractStatus.ACTIVE),PageRequest.of(0,10)).getTotalElements());
        contracts.delete(leaseEnvelope);contracts.flush();assertFalse(contracts.existsByRentalRequestId("rental"));
        assertTrue(contracts.findByRentalRequestId("rental").isEmpty());
    }
    private ContractTemplate template(String kind) {
        return templates.saveAndFlush(ContractTemplate.builder().name(kind).documentKind(kind).category(ListingCategory.HOUSE).build());
    }
    private ContractTemplateVersion version(ContractTemplate template) {
        var v=versions.saveAndFlush(ContractTemplateVersion.builder().template(template).versionNumber(1).storageObjectId("object")
            .status(TemplateVersionStatus.PUBLISHED).build());
        template.setLatestPublishedVersionId(v.getId());templates.saveAndFlush(template);return v;
    }
    private Contract envelope(String number,String kind,ContractTemplateVersion version) {
        return Contract.builder().contractNumber(number).agreementKind(kind).rentalRequestId("rental").listingId("listing")
            .landlordId("owner").tenantId("tenant").templateId(version.getTemplate().getId()).templateVersionId(version.getId())
            .status(ContractStatus.ACTIVE).build();
    }
}
