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
    @EnableJpaRepositories(basePackageClasses={ContractRepository.class,DepositAgreementRepository.class,com.hs.listing.repository.RentalRequestRepository.class})
    static class Config {
        @org.springframework.context.annotation.Bean
        DepositAgreementService agreementReader(DepositAgreementRepository agreements,ContractRepository contracts,
                com.hs.listing.repository.RentalRequestRepository rentals) {
            var time=org.mockito.Mockito.mock(com.hs.common.time.BillingTime.class);
            org.mockito.Mockito.when(time.now()).thenReturn(java.time.Instant.parse("2026-10-25T16:00:00Z"));
            return new DepositAgreementService(agreements,contracts,
                org.mockito.Mockito.mock(ContractRevisionRepository.class),org.mockito.Mockito.mock(ContractTemplateVersionRepository.class),rentals,
                org.mockito.Mockito.mock(com.hs.listing.repository.RentalOnboardingEventRepository.class),
                org.mockito.Mockito.mock(com.hs.payment.repository.DepositRecordRepository.class),
                org.mockito.Mockito.mock(com.hs.payment.repository.PaymentRequestRepository.class),
                org.mockito.Mockito.mock(com.hs.payment.repository.PaymentLineItemRepository.class),
                org.mockito.Mockito.mock(com.hs.contract.service.engine.ContractDataBuilder.class),
                new com.hs.contract.service.engine.ContractFieldCatalog(),new com.fasterxml.jackson.databind.ObjectMapper(),time);
        }
    }
    @Autowired ContractRepository contracts;
    @Autowired ContractTemplateRepository templates;
    @Autowired ContractTemplateVersionRepository versions;
    @Autowired DepositAgreementRepository deposits;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.hs.listing.repository.RentalRequestRepository rentals;
    @Autowired com.hs.listing.repository.ListingRepository listings;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired DepositAgreementService agreementReader;

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void readsTheLazyListingWithinTheServiceAndReturnsAUsableDtoAfterTheTransactionCloses() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
            var listing=listings.saveAndFlush(com.hs.listing.model.Listing.builder().id("read-listing").ownerId("owner")
                .title("Test room").description("Disposable integration fixture").category(ListingCategory.ROOM)
                .status(com.hs.listing.model.constant.ListingStatus.RESERVED).availableFrom(java.time.LocalDate.of(2026,10,25))
                .areaM2(java.math.BigDecimal.valueOf(20)).priceAmount(java.math.BigDecimal.valueOf(3400000)).currency("VND")
                .priceUnit(com.hs.listing.model.constant.PriceUnit.MONTH).depositType(com.hs.listing.model.constant.DepositType.NONE)
                .paymentCycle(com.hs.listing.model.constant.PaymentCycle.MONTHLY).minimumLeaseMonths(1).build());
            rentals.saveAndFlush(com.hs.listing.model.RentalRequest.builder().id("read-rental").listing(listing).ownerId("owner").renterId("tenant")
                .renterName("Test tenant").renterPhone("0900000000").moveInDate(java.time.LocalDate.of(2026,11,1)).leaseMonths(3)
                .monthlyRentPrice(java.math.BigDecimal.valueOf(3400000)).depositAmount(java.math.BigDecimal.valueOf(3400000))
                .status(com.hs.listing.model.constant.RentalRequestStatus.ACCEPTED).build());
        });
        var detached=rentals.findById("read-rental").orElseThrow();
        assertFalse(org.hibernate.Hibernate.isInitialized(detached.getListing()));
        assertThrows(org.hibernate.LazyInitializationException.class,()->detached.getListing().getStatus(),"Reproduce the old controller access with OSIV disabled");
        var result=agreementReader.overview("read-rental","tenant");
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(result.required());assertTrue(result.signingAllowed());assertNull(result.blockedReason());
        assertNull(result.signingContractId());
        assertThrows(com.hs.common.advice.entity.AppException.class,()->agreementReader.overview("read-rental","stranger"));
    }

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
    @Test void primaryAgreementSelectionPrefersTheReadableLeaseBeforeFilteringAndPagination() {
        var version=version(template("LEASE"));
        var deposit=contracts.saveAndFlush(envelope("DC-PRIMARY","DEPOSIT",version));
        assertEquals(List.of(deposit.getId()), primary("owner",ContractPartyRole.LANDLORD,null,0).getContent().stream().map(Contract::getId).toList());
        assertEquals(1, primary("tenant",ContractPartyRole.TENANT,null,0).getTotalElements());
        assertEquals(0, primary("stranger",ContractPartyRole.TENANT,null,0).getTotalElements());

        var lease=contracts.saveAndFlush(envelope("HD-PRIMARY",null,version));
        assertEquals(List.of(lease.getId()), primary("owner",ContractPartyRole.LANDLORD,null,0).getContent().stream().map(Contract::getId).toList());
        lease.setStatus(ContractStatus.DRAFT); contracts.saveAndFlush(lease);
        assertEquals(0, primary("owner",ContractPartyRole.LANDLORD,ContractStatus.ACTIVE,0).getTotalElements(),"A status filter must not resurrect the superseded deposit card");
        assertEquals(List.of(deposit.getId()), primary("tenant",ContractPartyRole.TENANT,null,0).getContent().stream().map(Contract::getId).toList(),"An unreadable draft must not hide the tenant's deposit agreement");
        lease.setStatus(ContractStatus.ACTIVE); contracts.saveAndFlush(lease);
        var second=envelope("DC-SECOND","DEPOSIT",version);second.setRentalRequestId("second-rental");contracts.saveAndFlush(second);
        var page1=primary("owner",ContractPartyRole.LANDLORD,null,0);var page2=primary("owner",ContractPartyRole.LANDLORD,null,1);
        assertEquals(2,page1.getTotalElements());assertEquals(2,page1.getTotalPages());
        assertNotEquals(page1.getContent().getFirst().getId(),page2.getContent().getFirst().getId());
        assertEquals(2,contracts.findDepositsForRentals(List.of("rental","second-rental")).size());
        assertEquals(1,contracts.findByParentContractIdIsNullAndStatusIn(Set.of(ContractStatus.ACTIVE),PageRequest.of(0,10)).getTotalElements(),"Billing/lifecycle query remains lease-only");
    }

    private org.springframework.data.domain.Page<Contract> primary(String actor,ContractPartyRole role,ContractStatus status,int page) {
        org.springframework.data.jpa.domain.Specification<Contract> spec=(root,query,cb)->cb.and(
            cb.equal(root.get(role==ContractPartyRole.LANDLORD?"landlordId":"tenantId"),actor),
            cb.isTrue(root.get("active")),cb.isNull(root.get("parentContractId")),
            role==ContractPartyRole.TENANT?com.hs.contract.service.ContractDraftVisibility.tenantReadable(root,query,cb):cb.conjunction(),
            com.hs.contract.service.ContractAgreementSelection.primary(root,query,cb,actor,role),
            status==null?cb.conjunction():cb.equal(root.get("status"),status));
        return contracts.findAll(spec,PageRequest.of(page,1,org.springframework.data.domain.Sort.by("id")));
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
