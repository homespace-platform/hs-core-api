package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.*;
import com.hs.common.time.BillingTime;
import com.hs.contract.deposit.DepositAgreementService;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.impl.ContractServiceImpl;
import com.hs.contract.service.engine.*;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicitly opt-in: create-drop schema only on the disposable phase-01 database. No provider calls. */
@EnabledIfEnvironmentVariable(named="HS_TEST_PG_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_phase01_test")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_TEST_PG_URL}","spring.datasource.username=${HS_TEST_PG_USER:postgres}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}","spring.jpa.hibernate.ddl-auto=create-drop","spring.profiles.active=test"})
@Import({ContractServiceImpl.class,ContractDraftIntegrity.class,com.hs.payment.service.DepositLedgerService.class})
class ContractDraftIntegrityPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={RentalRequestRepository.class,PaymentRequestRepository.class,ContractRepository.class})
    @EnableJpaAuditing static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean BillingTime time(Environment environment){return new BillingTime("2026-11-03T10:00:00",environment);}
    }
    @MockitoBean ListingStatusService listingStatus;
    @MockitoBean ContractDataBuilder builder;
    @MockitoBean ContractFieldCatalog catalog;
    @MockitoBean ContractRenderService renderer;
    @MockitoBean DocumentConversionService converter;
    @MockitoBean StorageService storage;
    @MockitoBean ParkingReservationService parking;
    @MockitoBean RentalOnboardingService onboarding;
    @MockitoBean DepositAgreementService deposits;
    @MockitoBean ContractAmendmentRenderer amendmentRenderer;
    @MockitoBean ContractAmendmentService amendments;
    @MockitoBean ContractTermsResolver terms;
    @MockitoSpyBean ContractDocumentRepository documents;
    @Autowired ContractServiceImpl service;
    @Autowired ContractRepository contracts;
    @Autowired ContractRevisionRepository revisions;
    @Autowired RentalRequestRepository rentals;
    @Autowired ListingRepository listings;
    @Autowired SignatureRequestRepository signatures;
    @Autowired PaymentRequestRepository payments;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    String contractId, rentalId, revisionId, listingId, documentId;

    @BeforeEach void fixture() throws Exception {
        UserContextHolder.set(new UserContext("owner","owner@test.invalid"));
        var listing=listings.saveAndFlush(Listing.builder().ownerId("owner").title("Phòng thử phase 01").description("Test")
                .category(ListingCategory.ROOM).status(ListingStatus.RESERVED).priceAmount(new BigDecimal("3000000"))
                .areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY)
                .availableFrom(LocalDate.of(2026,11,3)).minimumLeaseMonths(1)
                .depositType(DepositType.FIXED_AMOUNT).build());
        listingId=listing.getId();
        var rental=rentals.saveAndFlush(RentalRequest.builder().id(UUID.randomUUID().toString()).listing(listing).ownerId("owner").renterId("tenant")
                .renterName("Người thuê").renterPhone("0900000000").status(RentalRequestStatus.ACCEPTED)
                .moveInDate(LocalDate.of(2026,11,3)).leaseMonths(3).occupantCount(2).motorbikeCount(1).carCount(0)
                .monthlyRentPrice(new BigDecimal("3000000")).depositAmount(new BigDecimal("3000000")).build());
        rentalId=rental.getId();
        var c=contracts.saveAndFlush(Contract.builder().contractNumber("PHASE01-"+UUID.randomUUID()).rentalRequestId(rentalId)
                .listingId(listingId).landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("version").build());
        contractId=c.getId();
        var r=revisions.saveAndFlush(ContractRevision.builder().contract(c).revisionNumber(1).templateVersionId("version").schemaVersion(3)
                .landlordSnapshot("{}").tenantSnapshot("{\"occupantCount\":2,\"motorbikeCount\":1,\"carCount\":0}")
                .propertySnapshot("{}").leaseSnapshot(mapper.writeValueAsString(ContractDataBuilder.leaseTerms(rental)))
                .financialSnapshot(mapper.writeValueAsString(Map.of("amountValue","3000000","amountNumber","3.000.000 VNĐ/tháng",
                        "amountWords",VietnameseCurrencyTextConverter.toWords(new BigDecimal("3000000")),
                        "depositAmountValue","3000000","depositAmountNumber","3.000.000 VNĐ",
                        "depositAmountWords",VietnameseCurrencyTextConverter.toWords(new BigDecimal("3000000")),
                        "paymentDueDay",MonthlyBillingSchedule.paymentDueDescription(),"paymentDueOffsetDays",4)))
                .chargesSnapshot("[]").equipmentSnapshot("[]").amenitiesSnapshot("[]").policiesSnapshot("{\"latePaymentFeeMode\":\"NONE\"}")
                .initialMetersSnapshot("{\"electricityInitial\":\"0\",\"waterInitial\":\"0\"}").initialPaymentSnapshot("{}").specialTerms("Bản gốc").build());
        revisionId=r.getId();c.setCurrentRevisionId(revisionId);contracts.saveAndFlush(c);
        documentId=documents.saveAndFlush(ContractDocument.builder().contractId(contractId).revisionId(revisionId).templateVersionId("version")
                .documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.PREVIEW).status(DocumentGenerationStatus.READY)
                .storageObjectId("test-file").build()).getId();
        TestTransaction.flagForCommit();TestTransaction.end();
    }
    @AfterEach void clear(){UserContextHolder.clear();}
    UpdateContractRevisionRequest edit(String note) {return UpdateContractRevisionRequest.builder().baseRevisionId(revisionId).specialTerms(note).build();}
    @Test void validPartialSaveCreatesNewRevisionAndStalesPreviewWithoutChangingRentalOrListing() {
        var next=service.updateRevision(contractId,edit("Thỏa thuận mới"));
        assertEquals(2,next.getRevisionNumber());assertEquals(3,next.getSchemaVersion());
        assertEquals(3,next.getLease().get("durationMonths"));
        assertEquals(DocumentGenerationStatus.STALE,documents.findById(documentId).orElseThrow().getStatus());
        assertEquals("Bản gốc",revisions.findById(revisionId).orElseThrow().getSpecialTerms());
        assertEquals(3,rentals.findById(rentalId).orElseThrow().getLeaseMonths());
        assertEquals(ListingStatus.RESERVED,listings.findById(listingId).orElseThrow().getStatus());
        verifyNoInteractions(parking,listingStatus,storage,converter);
    }
    @Test void rejectsWrongTermBeforeAnyRevisionDocumentOrInventoryMutation() {
        assertEquals(6070,assertThrows(AppException.class,()->service.updateRevision(contractId,
                UpdateContractRevisionRequest.builder().lease(Map.of("durationMonths",12)).build())).getCode());
        assertUnchanged();
    }
    @Test void preExistingWrongRevisionCannotExportOrBeSignedInternally() {
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            var c=contracts.findByIdForUpdate(contractId).orElseThrow();
            var bad=ContractRevision.builder().contract(c).revisionNumber(2).templateVersionId("version").schemaVersion(3)
                    .landlordSnapshot("{}").tenantSnapshot("{\"occupantCount\":2,\"motorbikeCount\":1,\"carCount\":0}")
                    .propertySnapshot("{}").leaseSnapshot("{\"durationMonths\":12}").financialSnapshot("{}")
                    .chargesSnapshot("[]").equipmentSnapshot("[]").amenitiesSnapshot("[]").initialPaymentSnapshot("{}").build();
            bad=revisions.saveAndFlush(bad);c.setCurrentRevisionId(bad.getId());contracts.saveAndFlush(c);
        });
        assertEquals(6070,assertThrows(AppException.class,()->service.triggerPreview(contractId)).getCode());
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            var c=contracts.findByIdForUpdate(contractId).orElseThrow();c.setStatus(ContractStatus.PENDING_REVIEW);contracts.saveAndFlush(c);
        });
        UserContextHolder.set(new UserContext("tenant","tenant@test.invalid"));
        assertEquals(6070,assertThrows(AppException.class,()->service.sign(contractId)).getCode());
        assertEquals(ListingStatus.RESERVED,listings.findById(listingId).orElseThrow().getStatus());
        verifyNoInteractions(renderer,converter,storage,parking,listingStatus);
    }
    @Test void documentFailureRollsBackInsertedRevisionPointerAndPreviewStatusThenRetrySucceeds() {
        doThrow(new IllegalStateException("simulated document write failure")).when(documents).save(any(ContractDocument.class));
        assertThrows(IllegalStateException.class,()->service.updateRevision(contractId,edit("Lưu thử")));
        assertUnchanged();
        reset(documents); // Restore the Spring Data delegate; save() itself is an interface method.
        assertEquals(2,service.updateRevision(contractId,edit("Lưu lại")).getRevisionNumber());
    }
    @Test void twoEditorsCannotOverwriteEachOtherAfterWaitingOnDatabaseLocks() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Integer> attempt=()->{start.await();UserContextHolder.set(new UserContext("owner","owner@test.invalid"));
                try {service.updateRevision(contractId,edit("Một bản sửa"));return 0;}
                catch(AppException e){return e.getCode();}finally{UserContextHolder.clear();}};
            var a=executor.submit(attempt);var b=executor.submit(attempt);start.countDown();
            assertEquals(Set.of(0,6072),new HashSet<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))));
        }
        assertEquals(2,revisions.findByContractIdOrderByRevisionNumberDesc(contractId).size());
    }
    @Test void signingTransactionWinsLockAndWaitingEditCannotChangeItsRevision() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            var signing=executor.submit(()->new TransactionTemplate(transactions).execute(tx->{
                rentals.findByIdForUpdate(rentalId).orElseThrow();contracts.findByIdForUpdate(contractId).orElseThrow();
                signatures.saveAndFlush(SignatureRequest.builder().contractId(contractId).revisionId(revisionId).sourceDocumentId(documentId)
                        .signerUserId("owner").signerRole(SignerRole.LANDLORD).status(SignatureRequestStatus.CREATED).docId(UUID.randomUUID().toString()).build());
                locked.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;
            }));
            assertTrue(locked.await(15,TimeUnit.SECONDS));
            var editing=executor.submit(()->{UserContextHolder.set(new UserContext("owner","owner@test.invalid"));
                try{return assertThrows(AppException.class,()->service.updateRevision(contractId,edit("Không được ghi đè"))).getCode();}
                finally{UserContextHolder.clear();}});
            release.countDown();assertTrue(signing.get(20,TimeUnit.SECONDS));assertEquals(6028,editing.get(20,TimeUnit.SECONDS));
        }
        assertUnchanged();
    }
    void assertUnchanged(){
        assertEquals(revisionId,contracts.findById(contractId).orElseThrow().getCurrentRevisionId());
        assertEquals(1,revisions.findByContractIdOrderByRevisionNumberDesc(contractId).size());
        assertEquals(DocumentGenerationStatus.READY,documents.findById(documentId).orElseThrow().getStatus());
        assertEquals(ListingStatus.RESERVED,listings.findById(listingId).orElseThrow().getStatus());
    }
}
