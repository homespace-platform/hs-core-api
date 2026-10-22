package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.time.BillingTime;
import com.hs.contract.repository.*;
import com.hs.contract.service.engine.ContractDataBuilder;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentRequestService;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Run only against a throwaway database. Real schema, row locks, date updates and rollback. */
@EnabledIfEnvironmentVariable(named="HS_TEST_PG_URL",matches=".+")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_TEST_PG_URL}","spring.datasource.username=${HS_TEST_PG_USER:postgres}",
    "spring.datasource.password=${HS_TEST_PG_PASSWORD:test}","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false","spring.profiles.active=test"})
@Import({RentalOnboardingService.class,ParkingReservationService.class})
class OnboardingPersistencePostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={RentalRequestRepository.class,PaymentRequestRepository.class,ContractRepository.class})
    @EnableJpaAuditing static class Config {
        @Bean BillingTime time(Environment env){return new MutableTime(env);}
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
    }
    static class MutableTime extends BillingTime {
        Instant current=Instant.parse("2026-10-21T03:00:00Z");
        MutableTime(Environment env){super("2026-10-21T10:00:00",env);}
        @Override public Instant now(){return current;}
    }
    @MockitoBean PaymentRequestService paymentService;
    @MockitoBean ListingStatusService listingStatus;
    @MockitoBean RentalCostCalculator calculator;
    @MockitoBean ContractDataBuilder builder;
    @MockitoBean AutomaticContractDrafts automaticDrafts;
    @Autowired RentalOnboardingService service;
    @Autowired RentalRequestRepository rentals;
    @Autowired ListingRepository listings;
    @Autowired PaymentRequestRepository payments;
    @Autowired RentalStartChangeRepository changes;
    @Autowired ContractRepository contracts;
    @Autowired ParkingReservationRepository reservations;
    @Autowired EntityManager em;
    @Autowired BillingTime clock;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.hs.payment.repository.DepositRecordRepository deposits;
    @Autowired ContractRevisionRepository revisions;
    @Autowired ContractDocumentRepository documents;
    @Autowired ContractTemplateRepository templates;
    @Autowired ContractTemplateVersionRepository templateVersions;
    String id;
    @BeforeEach void fixture() {
        ((MutableTime)clock).current=Instant.parse("2026-10-21T03:00:00Z");
        var listing=Listing.builder().id(UUID.randomUUID().toString()).ownerId("owner").title("Phòng kiểm thử nhận nhà").description("Dữ liệu kiểm thử")
            .category(ListingCategory.ROOM).status(ListingStatus.RESERVED).priceAmount(new BigDecimal("5000000"))
            .availableFrom(LocalDate.of(2026,10,21)).areaM2(BigDecimal.TEN).currency("VND").minimumLeaseMonths(1)
            .priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY).depositType(DepositType.MONTH_COUNT).build();
        listing=listings.saveAndFlush(listing);
        var r=RentalRequest.builder().id(UUID.randomUUID().toString()).listing(listing).ownerId("owner").renterId("tenant")
            .renterName("Người thuê thử").renterPhone("0900000000").status(RentalRequestStatus.ACCEPTED)
            .moveInDate(LocalDate.of(2026,11,1)).leaseMonths(3).monthlyRentPrice(new BigDecimal("5000000"))
            .depositAmount(new BigDecimal("10000000")).build();
        id=rentals.saveAndFlush(r).getId();
        payments.saveAndFlush(PaymentRequest.builder().rentalRequestId(id).listingId(listing.getId()).payerId("tenant").payeeId("owner")
            .type(PaymentType.RESERVATION_DEPOSIT).onboardingKey(id+":RESERVATION_DEPOSIT").direction(PaymentDirection.TENANT_TO_LANDLORD)
            .status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("10000000")).transferReference("HS"+UUID.randomUUID().toString().substring(0,12))
            .confirmedAt(Instant.parse("2026-10-21T03:00:00Z")).build());
        em.clear();
    }
    @Test void defaultCompletionRollsBackFinancialAndParkingChangesThenRetriesSafely() {
        var r=rentals.findById(id).orElseThrow();r.setMoveInDate(LocalDate.of(2026,10,19));
        r.setReservationPolicyVersion("RESERVATION_DEFAULT_V1");r.setReservationPolicyAcceptedAt(clock.now().minusSeconds(200000));rentals.save(r);
        var source=payments.findByRentalRequestIdAndType(id,PaymentType.RESERVATION_DEPOSIT).orElseThrow();
        deposits.saveAndFlush(DepositRecord.builder().rentalRequestId(id).initialPaymentRequestId(source.getId()).landlordId("owner").tenantId("tenant")
            .originalAmount(new BigDecimal("10000000")).heldAmount(new BigDecimal("10000000")).refundableAmount(new BigDecimal("10000000")).status(DepositStatus.HELD).build());
        var rent=payments.saveAndFlush(PaymentRequest.builder().rentalRequestId(id).listingId(r.getListing().getId()).payerId("tenant").payeeId("owner")
            .type(PaymentType.INITIAL).onboardingKey(id+":INITIAL").direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.AWAITING_TRANSFER)
            .totalAmount(new BigDecimal("5000000")).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).dueAt(clock.now().minusSeconds(86400)).build());
        var c=contracts.saveAndFlush(com.hs.contract.model.Contract.builder().contractNumber("DEFAULT-"+UUID.randomUUID()).rentalRequestId(id)
            .listingId(r.getListing().getId()).landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("version").build());
        var revision=revisions.saveAndFlush(com.hs.contract.model.ContractRevision.builder().contract(c).revisionNumber(1).templateVersionId("version")
            .landlordSnapshot("{}").tenantSnapshot("{}").propertySnapshot("{}").leaseSnapshot("{}").financialSnapshot("{}").initialPaymentSnapshot("{}").build());
        c.setCurrentRevisionId(revision.getId());contracts.saveAndFlush(c);
        documents.saveAndFlush(com.hs.contract.model.ContractDocument.builder().contractId(c.getId()).revisionId(revision.getId()).templateVersionId("version")
            .documentType(com.hs.contract.model.constant.ContractDocumentType.PDF).purpose(com.hs.contract.model.constant.DocumentPurpose.PREVIEW)
            .status(com.hs.contract.model.constant.DocumentGenerationStatus.READY).storageObjectId("test-file")
            .renderFingerprint(ContractPreviewIdentity.fingerprint(revision,"INTERNAL")).build());
        var parkingHold=reservations.saveAndFlush(ParkingReservation.builder().rentalRequestId(id).listingId(r.getListing().getId()).vehicleType(VehicleType.MOTORBIKE)
            .quantity(1).startDate(r.getMoveInDate()).endDateExclusive(r.getMoveInDate().plusMonths(3)).status(ParkingReservationStatus.HELD).build());
        when(builder.onboardingPayment(any(),any(),anyMap(),anyList())).thenReturn(Map.of());
        var notified=service.notifyDefault(id,"owner",new RentalOnboardingService.Cancellation("Thông báo thanh toán tiền tháng đầu"));
        String contractId=c.getId(),rentId=rent.getId(),holdId=parkingHold.getId();
        TestTransaction.flagForCommit();TestTransaction.end();
        ((MutableTime)clock).current=notified.defaultCase().respondBy();
        doThrow(new IllegalStateException("Simulated release failure")).doNothing().when(listingStatus).releaseReserved(any(),eq("owner"),anyString());
        var completion=new RentalOnboardingService.DefaultCompletion("Khách không phản hồi sau thông báo",true,true);
        assertThrows(IllegalStateException.class,()->service.completeDefault(id,"owner",completion));
        TestTransaction.start();
        assertEquals(RentalRequestStatus.ACCEPTED,rentals.findById(id).orElseThrow().getStatus());
        assertEquals(com.hs.contract.model.constant.ContractStatus.DRAFT,contracts.findById(contractId).orElseThrow().getStatus());
        assertEquals(PaymentStatus.AWAITING_TRANSFER,payments.findById(rentId).orElseThrow().getStatus());
        assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(id).orElseThrow().getStatus());
        assertEquals(ParkingReservationStatus.HELD,reservations.findById(holdId).orElseThrow().getStatus());
        TestTransaction.end();
        service.completeDefault(id,"owner",completion);service.completeDefault(id,"owner",completion);
        TestTransaction.start();
        assertEquals(com.hs.contract.model.constant.ContractStatus.CANCELLED,contracts.findById(contractId).orElseThrow().getStatus());
        assertEquals(PaymentStatus.CANCELLED,payments.findById(rentId).orElseThrow().getStatus());
        var retained=deposits.findByRentalRequestId(id).orElseThrow();assertEquals(DepositStatus.RETAINED_BY_LANDLORD,retained.getStatus());
        assertEquals(0,new BigDecimal("10000000").compareTo(retained.getRetainedAmount()));
        assertEquals(ParkingReservationStatus.RELEASED,reservations.findById(holdId).orElseThrow().getStatus());
        verify(listingStatus,times(2)).releaseReserved(any(),eq("owner"),anyString());
    }
    @Test void immediateCancellationRollsBackThenReleasesOnlyOnceWithoutWaitingForDeadline() {
        var r=rentals.findById(id).orElseThrow();r.setHoldExpiresAt(clock.now().plusSeconds(3600));rentals.saveAndFlush(r);
        var deposit=payments.findByRentalRequestIdAndType(id,PaymentType.RESERVATION_DEPOSIT).orElseThrow();
        deposit.setStatus(PaymentStatus.AWAITING_TRANSFER);deposit.setConfirmedAt(null);payments.saveAndFlush(deposit);
        var hold=reservations.saveAndFlush(ParkingReservation.builder().rentalRequestId(id).listingId(r.getListing().getId())
            .vehicleType(VehicleType.MOTORBIKE).quantity(1).startDate(r.getMoveInDate()).endDateExclusive(r.getMoveInDate().plusMonths(3))
            .status(ParkingReservationStatus.HELD).build());
        String paymentId=deposit.getId(),holdId=hold.getId();
        TestTransaction.flagForCommit();TestTransaction.end();
        doThrow(new IllegalStateException("Simulated release failure")).doNothing().when(listingStatus).releaseReserved(any(),eq("owner"),anyString());
        assertThrows(IllegalStateException.class,()->service.cancelUnpaid(id,"owner"));
        TestTransaction.start();
        assertEquals(RentalRequestStatus.ACCEPTED,rentals.findById(id).orElseThrow().getStatus());
        assertEquals(PaymentStatus.AWAITING_TRANSFER,payments.findById(paymentId).orElseThrow().getStatus());
        assertEquals(ParkingReservationStatus.HELD,reservations.findById(holdId).orElseThrow().getStatus());
        TestTransaction.end();
        service.cancelUnpaid(id,"owner");service.cancelUnpaid(id,"tenant");
        TestTransaction.start();
        assertEquals(RentalRequestStatus.CANCELLED_BY_OWNER,rentals.findById(id).orElseThrow().getStatus());
        assertNull(rentals.findById(id).orElseThrow().getHoldExpiresAt());
        assertEquals(PaymentStatus.CANCELLED,payments.findById(paymentId).orElseThrow().getStatus());
        assertEquals(ParkingReservationStatus.RELEASED,reservations.findById(holdId).orElseThrow().getStatus());
        verify(listingStatus,times(2)).releaseReserved(any(),eq("owner"),anyString());
        verify(paymentService,never()).createRefundObligation(any());
        verify(paymentService,never()).createRentRefundObligation(any(),any());
    }
    @Test void ownerCancellationExpandsTheExistingStatusConstraintIdempotently() {
        // Only this test's throwaway schema: emulate the old Hibernate check before restarting Core.
        jdbc.execute("ALTER TABLE rental_requests DROP CONSTRAINT IF EXISTS rental_requests_status_check");
        jdbc.execute("ALTER TABLE rental_requests ADD CONSTRAINT rental_requests_status_check CHECK (status <> 'CANCELLED_BY_OWNER') NOT VALID");
        var migration=new com.hs.listing.config.RentalRequestStatusSchemaMigration(jdbc);
        migration.migrate();migration.migrate();
        var r=rentals.findById(id).orElseThrow();r.setStatus(RentalRequestStatus.CANCELLED_BY_OWNER);rentals.saveAndFlush(r);em.clear();
        assertEquals(RentalRequestStatus.CANCELLED_BY_OWNER,rentals.findById(id).orElseThrow().getStatus());
    }
    @Test void futureDepositCreatesNeitherFirstMonthNorDraft() {
        var v=service.refresh(id,"tenant");assertEquals("WAITING_START",v.stage());assertFalse(v.canPrepareContract());assertFalse(v.canSign());
        assertTrue(payments.findByRentalRequestIdAndType(id,PaymentType.INITIAL).isEmpty());
    }
    @Test void latestStartChangesAreBatchedDeterministicallyWithoutMixingRentalRequests() {
        var requested=clock.now();
        changes.saveAndFlush(RentalStartChange.builder().id("00000000-0000-0000-0000-000000000001").rentalRequestId(id)
            .previousDate(LocalDate.of(2026,11,1)).requestedDate(LocalDate.of(2026,10,25)).reason("Lần đề nghị trước")
            .status("WITHDRAWN").requestedAt(requested.minusSeconds(60)).respondBy(requested.plusSeconds(3600)).build());
        var latest=changes.saveAndFlush(RentalStartChange.builder().id("00000000-0000-0000-0000-000000000002").rentalRequestId(id)
            .previousDate(LocalDate.of(2026,11,1)).requestedDate(LocalDate.of(2026,10,26)).reason("Lần đề nghị mới nhất")
            .status("REQUESTED").requestedAt(requested).respondBy(requested.plusSeconds(3600)).build());
        var rows=changes.findLatestForRentalRequestIds(List.of(id));assertEquals(1,rows.size());assertEquals(latest.getId(),rows.getFirst().getId());
        assertTrue(changes.findLatestForRentalRequestIds(List.of("unrelated-request")).isEmpty());
        assertEquals("REQUESTED",changes.findById(latest.getId()).orElseThrow().getStatus());
    }
    private com.hs.contract.service.impl.ContractServiceImpl automaticCreator() {
        var versions=mock(ContractTemplateVersionRepository.class);
        when(versions.findCurrentPublishedSystemVersions(ListingCategory.ROOM)).thenReturn(List.of(
            com.hs.contract.model.ContractTemplateVersion.builder().id("auto-version")
                .template(com.hs.contract.model.ContractTemplate.builder().id("auto-template").build()).build()));
        return automaticCreator(versions,mock(com.hs.contract.service.converter.DocumentConversionService.class),mock(com.hs.storage.service.StorageService.class));
    }
    private com.hs.contract.service.impl.ContractServiceImpl automaticCreator(ContractTemplateVersionRepository versions,
            com.hs.contract.service.converter.DocumentConversionService converter,com.hs.storage.service.StorageService storage) {
        var target=new com.hs.contract.service.impl.ContractServiceImpl(contracts,revisions,documents,versions,rentals,listingStatus,builder,
            new com.hs.contract.service.engine.ContractFieldCatalog(),new com.hs.contract.service.engine.ContractRenderService(new ObjectMapper()),
            converter,storage,new ObjectMapper(),mock(ParkingReservationService.class),payments);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"billingTime",clock);
        var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
            transactions,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (com.hs.contract.service.impl.ContractServiceImpl)proxy.getProxy();
    }
    @Test void absentTemplateThenPublishedTemplateAndFailedPdfRecoverWithoutLosingThePaidDraft() throws Exception {
        paidFirstMonth();
        var converter=mock(com.hs.contract.service.converter.DocumentConversionService.class);
        var storage=mock(com.hs.storage.service.StorageService.class);
        var creator=automaticCreator(templateVersions,converter,storage);
        assertEquals(com.hs.contract.advice.ContractErrorCode.SYSTEM_TEMPLATE_NOT_AVAILABLE,
            ContractDraftTemplates.problem(templateVersions,ListingCategory.ROOM));
        TestTransaction.flagForCommit();TestTransaction.end();
        assertThrows(com.hs.common.advice.entity.AppException.class,()->creator.createDraftAutomatically(id));
        TestTransaction.start();assertTrue(contracts.findByRentalRequestId(id).isEmpty());
        var template=templates.saveAndFlush(com.hs.contract.model.ContractTemplate.builder().name("Test template")
            .category(ListingCategory.ROOM).build());
        var version=templateVersions.saveAndFlush(com.hs.contract.model.ContractTemplateVersion.builder().template(template).versionNumber(1)
            .storageObjectId("test-source").placeholdersJson("[\"contract.number\"]").build());
        assertEquals(com.hs.contract.advice.ContractErrorCode.SYSTEM_TEMPLATE_NOT_AVAILABLE,
            ContractDraftTemplates.problem(templateVersions,ListingCategory.ROOM));
        version.setStatus(com.hs.contract.model.constant.TemplateVersionStatus.PUBLISHED);templateVersions.saveAndFlush(version);
        template.setLatestPublishedVersionId(version.getId());templates.saveAndFlush(template);
        assertNull(ContractDraftTemplates.problem(templateVersions,ListingCategory.ROOM));
        assertEquals(com.hs.contract.advice.ContractErrorCode.SYSTEM_TEMPLATE_NOT_AVAILABLE,
            ContractDraftTemplates.problem(templateVersions,ListingCategory.HOUSE));
        TestTransaction.flagForCommit();TestTransaction.end();
        var draft=creator.createDraftAutomatically(id);
        try(var word=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("Contract {{contract.number}}");word.write(out);
            when(storage.downloadDirect("test-source")).thenReturn(out.toByteArray());
        }
        when(converter.convertDocxToPdf(any(),anyString())).thenReturn(Optional.empty(),Optional.of("%PDF-test".getBytes()));
        when(storage.uploadDirect(any(),anyString(),anyString(),any(),anyString(),anyString(),any(),eq("owner"))).thenAnswer(inv->{
            var response=mock(com.hs.storage.dto.response.StorageObjectResponse.class);when(response.id()).thenReturn(UUID.randomUUID().toString());return response;
        });
        assertThrows(com.hs.common.advice.entity.AppException.class,()->creator.prepareDraftPreviewAutomatically(id,draft.getId()));
        TestTransaction.start();
        assertEquals(draft.getId(),contracts.findByRentalRequestId(id).orElseThrow().getId());
        assertTrue(documents.findByContractIdAndRevisionId(draft.getId(),draft.getCurrentRevisionId()).isEmpty());
        assertEquals(PaymentStatus.CONFIRMED,payments.findByRentalRequestIdAndType(id,PaymentType.INITIAL).orElseThrow().getStatus());
        TestTransaction.end();
        var pdf=creator.prepareDraftPreviewAutomatically(id,draft.getId());
        assertEquals(pdf.getId(),creator.prepareDraftPreviewAutomatically(id,draft.getId()).getId());
        verify(converter,times(2)).convertDocxToPdf(any(),anyString());
        TestTransaction.start();assertEquals(2,documents.findByContractIdAndRevisionId(draft.getId(),draft.getCurrentRevisionId()).size());
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private void paidFirstMonth() {
        ((MutableTime)clock).current=Instant.parse("2026-11-01T03:00:00Z");
        payments.saveAndFlush(PaymentRequest.builder().rentalRequestId(id).payerId("tenant").payeeId("owner")
            .type(PaymentType.INITIAL).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED)
            .totalAmount(new BigDecimal("5000000")).transferReference("HS"+UUID.randomUUID().toString().substring(0,12))
            .confirmedAt(clock.now()).build());
        doReturn(ContractDataBuilder.ContractSnapshots.builder().build()).when(builder).build(any(),any(),any());
    }
    @Test void automaticDraftDoesNotCreateAnythingForDepositAlone() {
        var creator=automaticCreator();TestTransaction.flagForCommit();TestTransaction.end();
        assertNull(creator.createDraftAutomatically(id));
        TestTransaction.start();assertTrue(contracts.findByRentalRequestId(id).isEmpty());
    }
    @Test void concurrentAutomaticAttemptsPersistExactlyOneRootAndRevision() throws Exception {
        paidFirstMonth();var creator=automaticCreator();TestTransaction.flagForCommit();TestTransaction.end();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var futures=new ArrayList<java.util.concurrent.Future<String>>();
            for(int n=0;n<4;n++)futures.add(pool.submit(()->{start.await();return creator.createDraftAutomatically(id).getId();}));
            start.countDown();var ids=new HashSet<String>();
            for(var future:futures)ids.add(future.get(30,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,ids.size());
        }
        TestTransaction.start();
        var root=contracts.findByRentalRequestId(id).orElseThrow();
        assertTrue(root.getContractNumber().startsWith("HD-20261101-"));
        assertEquals(1L,jdbc.queryForObject("select count(*) from contracts where rental_request_id = ? and parent_contract_id is null",Long.class,id));
        assertEquals(1L,jdbc.queryForObject("select count(*) from contract_revisions where contract_id = ?",Long.class,root.getId()));
        assertEquals(root.getId(),payments.findByRentalRequestIdAndType(id,PaymentType.INITIAL).orElseThrow().getContractId());
        assertEquals(root.getId(),payments.findByRentalRequestIdAndType(id,PaymentType.RESERVATION_DEPOSIT).orElseThrow().getContractId());
        assertEquals(RentalRequestStatus.ACCEPTED,rentals.findById(id).orElseThrow().getStatus());
    }
    @Test void snapshotFailureRollsBackOnlyDraftThenRetryRecovers() {
        paidFirstMonth();var creator=automaticCreator();TestTransaction.flagForCommit();TestTransaction.end();
        when(builder.build(any(),any(),any())).thenThrow(new IllegalStateException("snapshot temporarily unavailable"));
        assertThrows(IllegalStateException.class,()->creator.createDraftAutomatically(id));
        TestTransaction.start();assertTrue(contracts.findByRentalRequestId(id).isEmpty());
        var rent=payments.findByRentalRequestIdAndType(id,PaymentType.INITIAL).orElseThrow();
        assertEquals(PaymentStatus.CONFIRMED,rent.getStatus());assertNull(rent.getContractId());TestTransaction.end();
        doReturn(ContractDataBuilder.ContractSnapshots.builder().build()).when(builder).build(any(),any(),any());
        assertNotNull(creator.createDraftAutomatically(id));
        TestTransaction.start();assertTrue(contracts.findByRentalRequestId(id).isPresent());
    }
    @Test void tenantDraftPaginationRequiresAcceptedRentalAndConfirmedPaymentForTheSameParties() {
        var r=rentals.findById(id).orElseThrow();
        var draft=com.hs.contract.model.Contract.builder().id(UUID.randomUUID().toString()).contractNumber("TEST-"+UUID.randomUUID())
            .rentalRequestId(id).listingId(r.getListing().getId()).landlordId("owner").tenantId("tenant")
            .templateId("template").templateVersionId("version").status(com.hs.contract.model.constant.ContractStatus.DRAFT).build();
        org.springframework.data.jpa.domain.Specification<com.hs.contract.model.Contract> visible=(root,query,cb)->cb.and(
            cb.equal(root.get("tenantId"),"tenant"),cb.isNull(root.get("parentContractId")),ContractDraftVisibility.tenantReadable(root,query,cb));
        long baseline=contracts.count(visible); // Other tests deliberately commit cancellation/rollback fixtures.
        contracts.saveAndFlush(draft);
        assertEquals(baseline+1,contracts.findAll(visible,org.springframework.data.domain.PageRequest.of(0,10)).getTotalElements());
        var deposit=payments.findByRentalRequestIdAndType(id,PaymentType.RESERVATION_DEPOSIT).orElseThrow();
        deposit.setStatus(PaymentStatus.TRANSFER_REPORTED);payments.saveAndFlush(deposit);
        assertEquals(baseline,contracts.count(visible));
        deposit.setStatus(PaymentStatus.CONFIRMED);deposit.setPayeeId("other-owner");payments.saveAndFlush(deposit);
        assertEquals(baseline,contracts.count(visible));
        deposit.setPayeeId("owner");payments.saveAndFlush(deposit);r.setStatus(RentalRequestStatus.PENDING);rentals.saveAndFlush(r);
        assertEquals(baseline,contracts.count(visible));
    }
    @Test void earlierStartPersistsCalendarAndAuditWithoutChangingPrice() {
        service.requestEarlier(id,"tenant",new RentalOnboardingService.StartRequest(LocalDate.of(2026,10,25),"Thống nhất nhận nhà sớm"));
        String change=changes.findByRentalRequestIdOrderByRequestedAtDesc(id).getFirst().getId();
        service.decideEarlier(id,change,"owner",new RentalOnboardingService.Decision(true,null));em.flush();em.clear();
        var r=rentals.findById(id).orElseThrow();assertEquals(LocalDate.of(2026,10,25),r.getMoveInDate());assertEquals(new BigDecimal("5000000.00"),r.getMonthlyRentPrice());
        assertEquals("APPROVED",changes.findById(change).orElseThrow().getStatus());
        assertEquals(LocalDate.of(2027,1,25),service.refresh(id,"owner").endExclusive());
    }
    @Test void failedCapacityDecisionRollsBackBothDateAndDecision() {
        service.requestEarlier(id,"tenant",new RentalOnboardingService.StartRequest(LocalDate.of(2026,10,25),"Thống nhất nhận nhà sớm"));
        String change=changes.findByRentalRequestIdOrderByRequestedAtDesc(id).getFirst().getId();
        var r=rentals.findById(id).orElseThrow();
        var hold=reservations.saveAndFlush(ParkingReservation.builder().id(UUID.randomUUID().toString()).rentalRequestId(id)
            .listingId(r.getListing().getId()).vehicleType(VehicleType.MOTORBIKE).quantity(1)
            .startDate(r.getMoveInDate()).endDateExclusive(r.getMoveInDate().plusMonths(3)).status(ParkingReservationStatus.HELD).build());
        String holdId=hold.getId();
        // Commit only the fixture so the failing public service has its own real transaction.
        TestTransaction.flagForCommit();TestTransaction.end();
        doThrow(new IllegalStateException("Không đủ chỗ xe")).when(calculator).calculate(any(),any(),anyInt(),anyInt(),anyInt(),anyInt(),any());
        assertThrows(IllegalStateException.class,()->service.decideEarlier(id,change,"owner",new RentalOnboardingService.Decision(true,null)));
        TestTransaction.start();
        assertEquals(LocalDate.of(2026,11,1),rentals.findById(id).orElseThrow().getMoveInDate());
        assertEquals("REQUESTED",changes.findById(change).orElseThrow().getStatus());
        assertEquals(ParkingReservationStatus.HELD,reservations.findById(holdId).orElseThrow().getStatus());
    }
}
