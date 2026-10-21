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
    @Test void futureDepositCreatesNoFirstMonthAndCanPrepareDraft() {
        var v=service.refresh(id,"tenant");assertEquals("WAITING_START",v.stage());assertTrue(v.canPrepareContract());assertFalse(v.canSign());
        assertTrue(payments.findByRentalRequestIdAndType(id,PaymentType.INITIAL).isEmpty());
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
