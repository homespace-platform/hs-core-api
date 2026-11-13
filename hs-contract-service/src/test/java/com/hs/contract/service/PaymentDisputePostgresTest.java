package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.*;
import com.hs.common.time.BillingTime;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.payment.dto.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.*;
import com.hs.payment.config.PaymentEventTypeSchemaMigration;
import com.hs.payment.service.qr.VietQrProvider;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Creates/drops schema only in an explicitly named disposable database. No S3/bank/provider requests. */
@EnabledIfEnvironmentVariable(named="HS_DISPUTE_TEST_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_phase02_test")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_DISPUTE_TEST_PG_URL}","spring.datasource.username=postgres",
    "spring.datasource.password=hs_phase02_test","spring.jpa.hibernate.ddl-auto=create-drop","spring.profiles.active=test"})
@Import({com.hs.listing.notification.RentalRequestNotificationWriter.class,PaymentRequestService.class,PaymentDisputeService.class})
class PaymentDisputePostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={com.hs.listing.notification.RentalRequestNotificationOutboxRepository.class,PaymentRequestRepository.class,RentalRequestRepository.class,ContractRepository.class,StorageObjectRepository.class})
    @EnableJpaAuditing static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean BillingTime time(Environment env){return new BillingTime("2026-11-03T10:00:00",env);}
    }
    @MockitoBean BankAccountService banks;
    @MockitoBean VietQrProvider qr;
    @MockitoBean TransferReferenceGenerator references;
    @MockitoBean PaymentProofUploadSessionService uploadSessions;
    @MockitoSpyBean DisputeCaseEventRepository events;
    @Autowired PaymentRequestService paymentService;
    @Autowired PaymentDisputeService casesService;
    @Autowired PaymentRequestRepository payments;
    @Autowired PaymentEvidenceRepository evidence;
    @Autowired PaymentEventRepository paymentEvents;
    @Autowired DisputeCaseRepository cases;
    @Autowired DepositRecordRepository deposits;
    @Autowired StorageObjectRepository storage;
    @Autowired ContractRepository contracts;
    @Autowired MonthlyInvoiceRepository invoices;
    @Autowired ContractClosureRepository closures;
    @Autowired RentalRequestRepository rentals;
    @Autowired ListingRepository listings;
    @Autowired ObjectMapper mapper;
    @Autowired BillingTime time;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    String paymentId,proofId,rentalId,contractId;
    final BigDecimal amount=new BigDecimal("3000000");
    @BeforeEach void fixture(){
        rentalId=UUID.randomUUID().toString();
        var c=contracts.saveAndFlush(Contract.builder().contractNumber("DISPUTE-"+UUID.randomUUID()).rentalRequestId(rentalId)
            .listingId(UUID.randomUUID().toString()).landlordId("owner").tenantId("tenant").templateId("template")
            .templateVersionId("version").status(ContractStatus.ACTIVE).build());
        contractId=c.getId();
        var p=payments.saveAndFlush(PaymentRequest.builder().rentalRequestId(rentalId).contractId(contractId).listingId(c.getListingId())
            .payerId("tenant").payeeId("owner").type(PaymentType.MONTHLY_RENT).direction(PaymentDirection.TENANT_TO_LANDLORD)
            .status(PaymentStatus.TRANSFER_REPORTED).totalAmount(amount).transferReference("HS"+UUID.randomUUID().toString().replace("-","").substring(0,15))
            .dueAt(time.now().minusSeconds(3600)).payerReportedAt(time.now().minusSeconds(120)).build());
        paymentId=p.getId();proofId=UUID.randomUUID().toString();
        storage.saveAndFlush(StorageObject.builder().id(proofId).ownerId("tenant").originalName("proof.pdf").objectKey("test/"+proofId)
            .bucketName("test-only").contentType("application/pdf").sizeBytes(100L).referenceType("PAYMENT_REQUEST").referenceId(paymentId)
            .purpose(StoragePurpose.PAYMENT_PROOF).visibility(StorageVisibility.PRIVATE).status(StorageStatus.READY).build());
        evidence.saveAndFlush(PaymentEvidence.builder().paymentRequestId(paymentId).uploadedBy("tenant").storageObjectId(proofId).build());
        TestTransaction.flagForCommit();TestTransaction.end();
    }
    @AfterEach void clear(){UserContextHolder.clear();SecurityContextHolder.clearContext();}
    DisputeCase open(){paymentService.dispute(paymentId,"tenant",new DisputePaymentRequest("Cần kiểm tra chứng từ và khoản đã chuyển"));return active();}
    DisputeCase active(){return cases.findByActiveKey("PAYMENT:"+paymentId).orElseThrow();}
    DisputeCaseAction payload(DisputeCase c,DisputeResolution resolution){return new DisputeCaseAction(c.getRevision(),"Đã kiểm tra hồ sơ và chứng cứ liên quan",resolution,null);}
    void resolve(DisputeResolution resolution){var c=open();casesService.act(c.getId(),"tenant","PROPOSE",payload(c,resolution));c=active();casesService.act(c.getId(),"owner","ACCEPT",payload(c,null));}
    void tx(Runnable action){new TransactionTemplate(transactions).executeWithoutResult(t->action.run());}
    void admin(String actor){UserContextHolder.set(new UserContext(actor,null));SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(actor,"n/a",List.of(new SimpleGrantedAuthority("ADMIN"))));}

    @Test void openingExistingApiCreatesCaseAndHistoryInSameTransaction(){
        var c=open();assertEquals(PaymentStatus.TRANSFER_REPORTED,c.getPreviousPaymentStatus());assertEquals(PaymentStatus.DISPUTED,payments.findById(paymentId).orElseThrow().getStatus());
        assertEquals(1,events.findByCaseIdOrderByRevisionAsc(c.getId()).size());assertEquals(0,c.getAmountSnapshot().compareTo(amount));
    }
    String eventCheck(){return jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid='payment_events'::regclass and conname='payment_events_event_type_check'",String.class);}
    void legacyEventCheck(boolean allowUnknown){
        String values=Arrays.stream(PaymentEventType.values()).filter(v->v!=PaymentEventType.DISPUTE_ROUTED)
            .map(v->"'"+v.name()+"'").collect(java.util.stream.Collectors.joining(","));
        jdbc.execute("ALTER TABLE payment_events DROP CONSTRAINT payment_events_event_type_check");
        jdbc.execute("ALTER TABLE payment_events ADD CONSTRAINT payment_events_event_type_check CHECK (event_type IN ("+values+(allowUnknown?",'LEGACY_UNKNOWN'":"")+")) NOT VALID");
    }
    @Test void migrationExpandsExistingEnumCheckAndIsIdempotentWithoutDeletingEvents(){
        String id=UUID.randomUUID().toString();
        tx(()->{
            legacyEventCheck(false);
            assertFalse(eventCheck().contains("'DISPUTE_ROUTED'"));
            var migration=new PaymentEventTypeSchemaMigration(jdbc);migration.migrate();migration.migrate();
            jdbc.update("insert into payment_events(id,payment_request_id,event_type,actor_id,created_at) values (?,?,?,?,current_timestamp)",id,paymentId,"DISPUTE_ROUTED","owner");
        });
        assertTrue(eventCheck().contains("'DISPUTE_ROUTED'"));
        assertEquals(1,jdbc.queryForObject("select count(*) from payment_events where id=?",Integer.class,id));
    }
    @Test void migrationValidationFailureRollsBackConstraintAndPreservesUnknownHistory(){
        String id=UUID.randomUUID().toString();
        tx(()->{
            legacyEventCheck(true);
            jdbc.update("insert into payment_events(id,payment_request_id,event_type,actor_id,created_at) values (?,?,?,?,current_timestamp)",id,paymentId,"LEGACY_UNKNOWN","owner");
        });
        String previous=eventCheck();
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx(()->new PaymentEventTypeSchemaMigration(jdbc).migrate()));
            assertEquals(previous,eventCheck());
            assertEquals(1,jdbc.queryForObject("select count(*) from payment_events where id=?",Integer.class,id));
        } finally {
            tx(()->{jdbc.update("delete from payment_events where id=?",id);new PaymentEventTypeSchemaMigration(jdbc).migrate();});
        }
    }
    @Test void eventFailureRollsBackOriginalDisputeToo(){
        doThrow(new IllegalStateException("audit unavailable")).when(events).save(any(DisputeCaseEvent.class));
        assertThrows(IllegalStateException.class,this::open);
        assertEquals(PaymentStatus.TRANSFER_REPORTED,payments.findById(paymentId).orElseThrow().getStatus());
        assertTrue(cases.findByActiveKey("PAYMENT:"+paymentId).isEmpty());
        assertTrue(paymentEvents.findByPaymentRequestIdOrderByCreatedAtAsc(paymentId).isEmpty());
    }
    @Test void bilateralReviewThenActualReceiptCompletesRealMonthlyInvoiceReconciliation(){
        final String[] invoiceId={null};
        tx(()->{var invoice=invoices.saveAndFlush(MonthlyInvoice.builder().contractId(contractId).periodIndex(1)
            .periodStart(LocalDate.of(2026,10,3)).periodEndExclusive(LocalDate.of(2026,11,3)).status(MonthlyInvoiceStatus.UNPAID)
            .totalAmount(amount).paymentRequestId(paymentId).dueAt(time.now().minusSeconds(3600)).build());
            invoiceId[0]=invoice.getId();var p=payments.findByIdForUpdate(paymentId).orElseThrow();p.setInvoiceId(invoiceId[0]);payments.saveAndFlush(p);});
        resolve(DisputeResolution.REVIEW_TRANSFER);
        assertNull(payments.findById(paymentId).orElseThrow().getConfirmedAt());
        assertEquals(MonthlyInvoiceStatus.UNPAID,invoices.findById(invoiceId[0]).orElseThrow().getStatus());
        assertEquals(7005,assertThrows(AppException.class,()->paymentService.confirmReceipt(paymentId,"tenant")).getCode());
        paymentService.confirmReceipt(paymentId,"owner");
        var billing=mock(MonthlyBillingService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(billing,"payments",paymentService);ReflectionTestUtils.setField(billing,"invoices",invoices);
        ReflectionTestUtils.setField(billing,"contracts",contracts);ReflectionTestUtils.setField(billing,"mapper",mapper);ReflectionTestUtils.setField(billing,"time",time);
        tx(()->ReflectionTestUtils.invokeMethod(billing,"reconcile",invoices.findById(invoiceId[0]).orElseThrow()));
        assertEquals(MonthlyInvoiceStatus.PAID,invoices.findById(invoiceId[0]).orElseThrow().getStatus());
        assertEquals(time.now(),invoices.findById(invoiceId[0]).orElseThrow().getPaidAt());
    }
    @Test void refundCaseThenTenantReceiptCompletesRealDepositAndClosureSynchronization(){
        tx(()->{
            var p=payments.findByIdForUpdate(paymentId).orElseThrow();p.setType(PaymentType.DEPOSIT_REFUND);p.setDirection(PaymentDirection.LANDLORD_TO_TENANT);
            p.setPayerId("owner");p.setPayeeId("tenant");payments.saveAndFlush(p);
            var f=storage.findById(proofId).orElseThrow();f.setOwnerId("owner");storage.saveAndFlush(f);
            evidence.findByPaymentRequestIdOrderByCreatedAtDesc(paymentId).forEach(e->{e.setUploadedBy("owner");evidence.saveAndFlush(e);});
            deposits.saveAndFlush(DepositRecord.builder().rentalRequestId(rentalId).contractId(contractId).initialPaymentRequestId("source")
                .landlordId("owner").tenantId("tenant").originalAmount(new BigDecimal("3100000")).heldAmount(amount).refundableAmount(amount)
                .retainedAmount(new BigDecimal("100000")).refundPaymentRequestId(paymentId).status(DepositStatus.REFUND_REPORTED).build());
            var c=contracts.findByIdForUpdate(contractId).orElseThrow();c.setStatus(ContractStatus.EXPIRED);contracts.saveAndFlush(c);
            closures.saveAndFlush(ContractClosure.builder().contractId(contractId).status("REFUND_PENDING").refundPaymentId(paymentId)
                .refundAmount(amount).acceptedAt(time.now()).proposalVersion(1).deductionsSnapshot("[]").historySnapshot("[]").build());
        });
        var c=open();assertEquals(DepositStatus.REFUND_REPORTED,c.getPreviousDepositStatus());
        casesService.act(c.getId(),"owner","PROPOSE",payload(c,DisputeResolution.REVIEW_TRANSFER));c=active();
        casesService.act(c.getId(),"tenant","ACCEPT",payload(c,null));
        assertEquals(DepositStatus.REFUND_REPORTED,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
        assertEquals(ContractStatus.EXPIRED,contracts.findById(contractId).orElseThrow().getStatus());
        paymentService.confirmReceipt(paymentId,"tenant");
        var lifecycle=mock(ContractLifecycleService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(lifecycle,"contracts",contracts);ReflectionTestUtils.setField(lifecycle,"closures",closures);
        ReflectionTestUtils.setField(lifecycle,"renewals",mock(ContractRenewalRepository.class));ReflectionTestUtils.setField(lifecycle,"invoices",invoices);
        ReflectionTestUtils.setField(lifecycle,"paymentRepository",payments);ReflectionTestUtils.setField(lifecycle,"payments",paymentService);
        ReflectionTestUtils.setField(lifecycle,"time",time);ReflectionTestUtils.setField(lifecycle,"mapper",mapper);
        tx(()->ReflectionTestUtils.invokeMethod(lifecycle,"syncLocked",contracts.findByIdForUpdate(contractId).orElseThrow()));
        var ledger=deposits.findByRentalRequestId(rentalId).orElseThrow();assertEquals(DepositStatus.REFUNDED,ledger.getStatus());
        assertEquals(0,ledger.getHeldAmount().signum());assertEquals(0,ledger.getRetainedAmount().compareTo(new BigDecimal("100000")));
        assertEquals("CLOSED",closures.findById(contractId).orElseThrow().getStatus());assertEquals(ContractStatus.CLOSED,contracts.findById(contractId).orElseThrow().getStatus());
    }
    @ParameterizedTest @EnumSource(PaymentDirection.class)
    void depositDeltaIsAppliedExactlyOnceByExistingReceiptPathAfterReopening(PaymentDirection direction){
        tx(()->{
            var p=payments.findByIdForUpdate(paymentId).orElseThrow();p.setType(PaymentType.DEPOSIT_ADJUSTMENT);p.setDirection(direction);
            if(direction==PaymentDirection.LANDLORD_TO_TENANT){p.setPayerId("owner");p.setPayeeId("tenant");}
            payments.saveAndFlush(p);
            deposits.saveAndFlush(DepositRecord.builder().rentalRequestId(rentalId).contractId(contractId).initialPaymentRequestId("source")
                .landlordId("owner").tenantId("tenant").originalAmount(new BigDecimal("5000000")).heldAmount(new BigDecimal("5000000"))
                .refundableAmount(new BigDecimal("5000000")).status(DepositStatus.HELD).build());
        });
        var p=payments.findById(paymentId).orElseThrow();var payer=p.getPayerId();var payee=p.getPayeeId();
        paymentService.dispute(paymentId,payer,new DisputePaymentRequest("Cần kiểm tra khoản chênh lệch tiền cọc"));
        var c=active();casesService.act(c.getId(),payer,"PROPOSE",payload(c,DisputeResolution.REOPEN_TRANSFER));c=active();casesService.act(c.getId(),payee,"ACCEPT",payload(c,null));
        assertEquals(0,deposits.findByRentalRequestId(rentalId).orElseThrow().getHeldAmount().compareTo(new BigDecimal("5000000")));
        tx(()->{var f=storage.findById(proofId).orElseThrow();f.setOwnerId(payer);storage.saveAndFlush(f);});
        paymentService.reportTransfer(paymentId,payer,ReportTransferRequest.builder().proofStorageId(proofId).expectedAmount(amount).build());
        paymentService.confirmReceipt(paymentId,payee);paymentService.confirmReceipt(paymentId,payee);
        assertEquals(0,deposits.findByRentalRequestId(rentalId).orElseThrow().getHeldAmount().compareTo(new BigDecimal(direction==PaymentDirection.TENANT_TO_LANDLORD?"8000000":"2000000")));
    }
    @Test void initialRefundRemainsPayableEvenWhenRentalAlreadyEnded(){
        tx(()->{
            var listing=listings.saveAndFlush(Listing.builder().ownerId("owner").title("Phòng đã cho khách khác thuê").description("Test only")
                .category(ListingCategory.ROOM).status(ListingStatus.RENTED).priceAmount(amount).areaM2(BigDecimal.TEN)
                .currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY).depositType(DepositType.NONE)
                .availableFrom(time.today()).minimumLeaseMonths(1).build());
            rentals.saveAndFlush(RentalRequest.builder().id(rentalId).listing(listing).ownerId("owner").renterId("tenant")
                .renterName("Người thuê").renterPhone("0900000000").status(RentalRequestStatus.CANCELLED_BY_OWNER)
                .moveInDate(time.today()).leaseMonths(3).monthlyRentPrice(amount).depositAmount(amount).build());
            var contract=contracts.findByIdForUpdate(contractId).orElseThrow();contract.setStatus(ContractStatus.CANCELLED);contracts.saveAndFlush(contract);
            var p=payments.findByIdForUpdate(paymentId).orElseThrow();p.setType(PaymentType.INITIAL_PAYMENT_REFUND);
            p.setDirection(PaymentDirection.LANDLORD_TO_TENANT);p.setPayerId("owner");p.setPayeeId("tenant");payments.saveAndFlush(p);
            var f=storage.findById(proofId).orElseThrow();f.setOwnerId("owner");storage.saveAndFlush(f);
            evidence.findByPaymentRequestIdOrderByCreatedAtDesc(paymentId).forEach(e->{e.setUploadedBy("owner");evidence.saveAndFlush(e);});});
        var c=open();casesService.act(c.getId(),"owner","PROPOSE",payload(c,DisputeResolution.REVIEW_TRANSFER));c=active();casesService.act(c.getId(),"tenant","ACCEPT",payload(c,null));
        paymentService.confirmReceipt(paymentId,"tenant");assertEquals(PaymentStatus.CONFIRMED,payments.findById(paymentId).orElseThrow().getStatus());
        assertEquals(RentalRequestStatus.CANCELLED_BY_OWNER,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(ContractStatus.CANCELLED,contracts.findById(contractId).orElseThrow().getStatus());
    }
    @Test void resolutionAuditFailureRollsBackPaymentCaseAndEventsThenRetrySucceeds(){
        var c=open();casesService.act(c.getId(),"tenant","PROPOSE",payload(c,DisputeResolution.REVIEW_TRANSFER));c=active();
        var id=c.getId();var action=payload(c,null);
        doThrow(new IllegalStateException("audit write failed")).when(events).save(any(DisputeCaseEvent.class));
        assertThrows(IllegalStateException.class,()->casesService.act(id,"owner","ACCEPT",action));
        assertEquals(PaymentStatus.DISPUTED,payments.findById(paymentId).orElseThrow().getStatus());assertEquals(2,active().getRevision());
        assertEquals(DisputeCaseStatus.OPEN,active().getStatus());
        reset(events);casesService.act(id,"owner","ACCEPT",action);
        assertEquals(PaymentStatus.TRANSFER_REPORTED,payments.findById(paymentId).orElseThrow().getStatus());
        assertEquals(3,events.findByCaseIdOrderByRevisionAsc(id).size());
    }
    @Test void simultaneousAcceptsApplySingleRoute() throws Exception {
        var c=open();casesService.act(c.getId(),"tenant","PROPOSE",payload(c,DisputeResolution.REVIEW_TRANSFER));c=active();
        final String id=c.getId();final DisputeCaseAction action=payload(c,null);
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);Callable<Integer> accept=()->{start.await();try{casesService.act(id,"owner","ACCEPT",action);return 0;}catch(AppException e){return e.getCode();}};
            var a=executor.submit(accept);var b=executor.submit(accept);start.countDown();
            assertEquals(Set.of(0,7026),new HashSet<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))));
        }
        assertEquals(1,paymentEvents.findByPaymentRequestIdOrderByCreatedAtAsc(paymentId).stream().filter(e->e.getEventType()==PaymentEventType.DISPUTE_ROUTED).count());
    }
    @Test void simultaneousAdminsCannotClaimSameReviewedVersion() throws Exception {
        var c=open();casesService.act(c.getId(),"tenant","ESCALATE",payload(c,null));c=active();final String id=c.getId();final DisputeCaseAction action=payload(c,null);
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Integer>>();
            for(String who:List.of("admin-one","admin-two"))futures.add(executor.submit(()->{admin(who);start.await();try{casesService.adminAct(id,who,"CLAIM",action);return 0;}catch(AppException e){return e.getCode();}finally{clear();}}));
            start.countDown();assertEquals(Set.of(0,7026),new HashSet<>(List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS))));
        }
        assertEquals(3,active().getRevision());assertNotNull(active().getAssignedTo());assertNull(payments.findById(paymentId).orElseThrow().getConfirmedAt());
    }
    @Test void staleManagedTransferCannotBeConfirmedAfterConcurrentDisputeCommits() throws Exception {
        try(var executor=Executors.newSingleThreadExecutor()){
            var loaded=new CountDownLatch(1);var proceed=new CountDownLatch(1);
            var confirm=executor.submit(()->{
                try { new TransactionTemplate(transactions).executeWithoutResult(tx->{
                    assertEquals(PaymentStatus.TRANSFER_REPORTED,payments.findById(paymentId).orElseThrow().getStatus());loaded.countDown();
                    try{assertTrue(proceed.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                    paymentService.confirmReceipt(paymentId,"owner");
                });return 0;}catch(AppException e){return e.getCode();}
            });
            assertTrue(loaded.await(15,TimeUnit.SECONDS));open();proceed.countDown();assertEquals(7006,confirm.get(20,TimeUnit.SECONDS));
        }
        assertEquals(PaymentStatus.DISPUTED,payments.findById(paymentId).orElseThrow().getStatus());assertNull(payments.findById(paymentId).orElseThrow().getConfirmedAt());
    }
    @Test void adminDecisionUnlocksReviewButAdminCannotConfirmReceiptOnBehalfOfReceiver(){
        var c=open();casesService.act(c.getId(),"tenant","ESCALATE",payload(c,null));c=active();admin("admin");
        casesService.adminAct(c.getId(),"admin","CLAIM",payload(c,null));c=active();
        casesService.adminAct(c.getId(),"admin","DECIDE",payload(c,DisputeResolution.REVIEW_TRANSFER));
        assertEquals(7005,assertThrows(AppException.class,()->paymentService.confirmReceipt(paymentId,"admin")).getCode());
        assertNull(payments.findById(paymentId).orElseThrow().getConfirmedAt());
        paymentService.confirmReceipt(paymentId,"owner");assertEquals(PaymentStatus.CONFIRMED,payments.findById(paymentId).orElseThrow().getStatus());
    }
}
