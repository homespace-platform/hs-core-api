package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.*;
import com.hs.contract.dto.complaint.*;
import com.hs.common.context.*;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.dto.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.*;
import com.hs.payment.service.qr.VietQrProvider;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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

/** Opt-in, disposable DB only. No bank, S3 or signing provider calls. */
@org.junit.jupiter.api.condition.EnabledIf("databaseIsAllowed")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.profiles.active=test"})
@Import({com.hs.contract.notification.InvoiceNotificationWriter.class,com.hs.contract.notification.MoveInHandoverNotificationWriter.class,com.hs.contract.notification.AgreementSigningNotificationWriter.class,com.hs.contract.notification.RenewalNotificationWriter.class,com.hs.contract.notification.TerminationNotificationWriter.class,com.hs.listing.notification.RentalRequestNotificationWriter.class,com.hs.payment.notification.PaymentNotificationWriter.class,ContractLifecycleService.class,MonthlyBillingService.class,FinalRentCalculator.class,WorkflowDocumentService.class,WorkflowDocumentCommitter.class,WorkflowDocumentRetentionChecker.class,PaymentRequestService.class,DepositLedgerService.class,PaymentDisputeService.class,ComplaintCaseService.class,ComplaintEvidenceAccessChecker.class,RentalWorkflowSupport.class,PreTenancyResolutionService.class,RentalOnboardingService.class,MoveInHandoverService.class,MutualTerminationService.class,RentalWorkflowEvidenceAccessChecker.class})
class RentalWorkflowPostgresTest {
    static final String LEGACY_URL="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_workflow_test";
    static final String DEDICATED_URL="jdbc:postgresql://[^/]+/core_(rental_notification|notification_rollout)_test_[A-Za-z0-9]+";
    static boolean legacyDatabase(){var url=System.getenv("HS_WORKFLOW_TEST_PG_URL");return url!=null&&url.matches(LEGACY_URL);}
    static boolean dedicatedDatabase(){var url=System.getenv("HS_TEST_PG_URL");return url!=null&&url.matches(DEDICATED_URL);}
    static boolean databaseIsAllowed(){return legacyDatabase()||dedicatedDatabase();}
    /** Database name inside the URL that databaseIsAllowed already validated (legacy or dedicated route). */
    static String selectedDatabaseName(){var url=System.getenv(legacyDatabase()?"HS_WORKFLOW_TEST_PG_URL":"HS_TEST_PG_URL");return url.substring(url.lastIndexOf('/')+1);}
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry registry){
        boolean legacy=legacyDatabase();
        registry.add("spring.datasource.url",()->System.getenv(legacy?"HS_WORKFLOW_TEST_PG_URL":"HS_TEST_PG_URL"));
        registry.add("spring.datasource.username",()->legacy?"postgres":System.getenv("HS_TEST_PG_USER"));
        registry.add("spring.datasource.password",()->legacy?"hs_workflow_test":System.getenv("HS_TEST_PG_PASSWORD"));
    }
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={com.hs.contract.notification.ContractNotificationOutboxRepository.class,com.hs.listing.notification.RentalRequestNotificationOutboxRepository.class,com.hs.payment.notification.PaymentNotificationOutboxRepository.class,ContractRepository.class,RentalRequestRepository.class,PaymentRequestRepository.class,StorageObjectRepository.class,com.hs.contract.deposit.DepositAgreementRepository.class})
    @EnableJpaAuditing static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean MutableTime time(Environment env){return new MutableTime(env);}
    }
    static class MutableTime extends BillingTime {
        volatile Instant instant;
        MutableTime(Environment env){super("2026-12-13T10:00:00",env);}
        @Override public Instant now(){return instant;}
        @Override public LocalDate today(){return now().atZone(BillingTime.ZONE).toLocalDate();}
    }
    @MockitoBean ContractAmendmentService amendments;
    @MockitoBean ContractTermsResolver terms;
    @MockitoBean ListingStatusService listings;
    @MockitoBean ParkingReservationService parking;
    @MockitoBean BankAccountService banks;
    @MockitoBean VietQrProvider qr;
    @MockitoBean TransferReferenceGenerator references;
    @MockitoBean PaymentProofUploadSessionService uploadSessions;
    @MockitoBean AutomaticContractDrafts automaticDrafts;
    @MockitoBean RentalCostCalculator rentalCostCalculator;
    @MockitoBean com.hs.contract.service.engine.ContractDataBuilder contractDataBuilder;
    @Autowired RentalOnboardingService onboarding;
    @Autowired ContractLifecycleService service;
    @Autowired ComplaintCaseService complaints;
    @Autowired ComplaintEvidenceAccessChecker access;
    @Autowired DisputeCaseRepository cases;
    @MockitoSpyBean DisputeCaseEventRepository caseEvents;
    @Autowired PaymentRequestService payments;
    @Autowired PaymentDisputeService disputes;
    @Autowired DepositLedgerService ledger;
    @Autowired ContractRepository contracts;
    @Autowired ContractRevisionRepository revisions;
    @Autowired ContractRenewalRepository renewals;
    @Autowired ContractClosureRepository closures;
    @Autowired RentalRequestRepository rentals;
    @Autowired ListingRepository properties;
    @Autowired DepositRecordRepository deposits;
    @Autowired PaymentRequestRepository paymentRepo;
    @Autowired MonthlyInvoiceRepository invoices;
    @Autowired SignatureRequestRepository signatures;
    @Autowired ContractDocumentRepository documents;
    @Autowired StorageObjectRepository storage;
    @Autowired MutableTime time;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    String id,rentalId,listingId;
    final BigDecimal principal=new BigDecimal("20000000");
    void clock(String local){time.instant=LocalDateTime.parse(local).atZone(BillingTime.ZONE).toInstant();}
    void tx(Runnable call){new TransactionTemplate(transactions).executeWithoutResult(t->call.run());}
    @BeforeEach void fixture(){
        clock("2026-12-13T10:00:00");
        var l=properties.saveAndFlush(Listing.builder().ownerId("owner").title("Phase03 test").description("Test only")
            .status(ListingStatus.RENTED).category(ListingCategory.ROOM).priceAmount(new BigDecimal("5000000"))
            .areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY)
            .depositType(DepositType.NONE).availableFrom(time.today()).minimumLeaseMonths(1).build());listingId=l.getId();
        var r=rentals.saveAndFlush(RentalRequest.builder().id(UUID.randomUUID().toString()).listing(l).ownerId("owner").renterId("tenant").renterName("Test")
            .renterPhone("0900000000").status(RentalRequestStatus.COMPLETED).moveInDate(LocalDate.of(2026,10,12))
            .leaseMonths(3).monthlyRentPrice(new BigDecimal("5000000")).depositAmount(principal).build());rentalId=r.getId();
        var c=contracts.saveAndFlush(Contract.builder().contractNumber("PH03-"+UUID.randomUUID()).rentalRequestId(rentalId).listingId(listingId)
            .landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("template-v1")
            .status(ContractStatus.ACTIVE).paymentStatus(ContractPaymentStatus.PAID).build());id=c.getId();
        var revision=revisions.saveAndFlush(ContractRevision.builder().contract(c).revisionNumber(1).templateVersionId("template-v1")
            .landlordSnapshot("{}").tenantSnapshot("{}").propertySnapshot("{}").leaseSnapshot("{\"durationMonths\":3}")
            .financialSnapshot("{}").chargesSnapshot("[]").policiesSnapshot("{\"depositRefundDays\":15}").build());
        c.setCurrentRevisionId(revision.getId());contracts.saveAndFlush(c);
        when(terms.at(any(),any())).thenAnswer(call->{Contract contract=call.getArgument(0);return revisions.findById(contract.getCurrentRevisionId()).orElseThrow();});
        var initial=paymentRepo.saveAndFlush(PaymentRequest.builder().rentalRequestId(rentalId).listingId(listingId).contractId(id)
            .payerId("tenant").payeeId("owner").type(PaymentType.RESERVATION_DEPOSIT).direction(PaymentDirection.TENANT_TO_LANDLORD)
            .status(PaymentStatus.CONFIRMED).totalAmount(principal).confirmedAt(time.now()).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());
        deposits.saveAndFlush(DepositRecord.builder().rentalRequestId(rentalId).contractId(id).initialPaymentRequestId(initial.getId())
            .landlordId("owner").tenantId("tenant").originalAmount(principal).heldAmount(principal).refundableAmount(principal).status(DepositStatus.HELD).build());
        for(int index=0;index<3;index++)invoices.saveAndFlush(MonthlyInvoice.builder().contractId(id).periodIndex(index)
            .periodStart(r.getMoveInDate().plusMonths(index)).periodEndExclusive(r.getMoveInDate().plusMonths(index+1))
            .status(MonthlyInvoiceStatus.PAID).totalAmount(BigDecimal.ZERO).build());
        var bank=BankAccount.builder().bankBin("970436").bankCode("VCB").bankName("Test").accountNumber("123456").accountHolderName("TEST").build();
        when(banks.getDefaultRefundAccount(anyString())).thenReturn(bank);when(banks.getDefaultIncomingAccount(anyString())).thenReturn(bank);
        when(references.generateUniqueReference()).thenAnswer(call->"HS"+UUID.randomUUID().toString().replace("-","").substring(0,15));
        TestTransaction.flagForCommit();TestTransaction.end();
    }

    @Autowired PreTenancyResolutionService cancellation;
    @Autowired MoveInHandoverService handover;
    @Autowired MutualTerminationService termination;
    @Autowired RentalWorkflowSupport support;
    @Autowired RentalWorkflowEvidenceAccessChecker workflowAccess;
    @MockitoSpyBean RentalWorkflowRecordRepository workflows;
    @Autowired MonthlyBillingService billing;
    @Autowired PaymentLineItemRepository lines;
    @Autowired WorkflowDocumentService workflowDocuments;
    @Autowired WorkflowSigningDocumentRepository workflowDocumentRecords;
    @Autowired WorkflowDocumentRetentionChecker documentRetention;
    @MockitoBean WorkflowPdfRenderer workflowRenderer;
    @MockitoBean com.hs.contract.pdf.PdfSignatureEngine pdfEngine;
    @MockitoBean com.hs.storage.service.StorageService storageService;
    @MockitoBean com.hs.contract.deposit.DepositAgreementService depositAgreements;
    @Autowired com.hs.contract.deposit.DepositAgreementRepository depositAgreementRecords;
    @Autowired RentalOnboardingEventRepository onboardingEvents;
    @Autowired com.hs.listing.notification.RentalRequestNotificationOutboxRepository notices;
    /** This rental's durable notices as TYPE:SIDE:recipient in enqueue order (per-request FIFO). */
    List<String> events(){
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        return notices.findAll().stream().filter(r->rentalId.equals(r.getRentalRequestId()))
            .sorted(java.util.Comparator.comparing(com.hs.listing.notification.RentalRequestNotificationOutbox::getId))
            .map(r->{var e=json.readTree(r.getPayload());return e.get("eventType").asText()+":"+e.get("data").get("recipientSide").asText()+":"+e.get("recipients").get(0).asText();}).toList();
    }
    @BeforeEach void signedFixture() throws Exception {when(workflowRenderer.render(any(),any(),any())).thenReturn("test PDF supplied by converter mock".getBytes(java.nio.charset.StandardCharsets.UTF_8));when(pdfEngine.addSignaturePage(any())).thenAnswer(c->c.getArgument(0));when(storageService.uploadDirect(any(byte[].class),anyString(),anyString(),any(),anyString(),anyString(),any(),anyString())).thenAnswer(c->{String file=UUID.randomUUID().toString();storage.saveAndFlush(StorageObject.builder().id(file).ownerId(c.getArgument(7)).originalName(c.getArgument(1)).objectKey("workflow/"+file).bucketName("test").contentType(c.getArgument(2)).sizeBytes((long)((byte[])c.getArgument(0)).length).referenceType(c.getArgument(4)).referenceId(c.getArgument(5)).purpose(c.getArgument(3)).visibility(c.getArgument(6)).status(StorageStatus.READY).build());var dto=mock(com.hs.storage.dto.response.StorageObjectResponse.class);when(dto.id()).thenReturn(file);return dto;});tx(()->{var c=contracts.findById(id).orElseThrow();c.setSignedAt(time.now().minusSeconds(30L*86400));contracts.save(c);
        var last=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();last.setStatus(MonthlyInvoiceStatus.DRAFT);invoices.save(last);});}
    RentalWorkflowPayloads.Action action(long revision){return new RentalWorkflowPayloads.Action(revision,"Đã đối chiếu nội dung và xác nhận thực tế",true,true);}
    RentalWorkflowPayloads.Termination early(long revision){return new RentalWorkflowPayloads.Termination(revision,LocalDate.of(2026,12,20),"Hai bên thỏa thuận kết thúc sớm", "Bàn giao phòng, chìa khóa và tài sản tại chỗ", "FULL_STARTED_PERIOD",List.of(),List.of());}
    RentalWorkflowPayloads.Handover move(long revision){return new RentalWorkflowPayloads.Handover(revision,time.now(),2,"Phòng đúng hiện trạng đã đối chiếu", "Biên bản nhận nhà thực tế đầu kỳ",BigDecimal.TEN,BigDecimal.ONE,List.of(new RentalWorkflowPayloads.Item("Chìa khóa",2,"Hoạt động tốt")),List.of());}
    void beforeLease(){tx(()->{var r=rentals.findById(rentalId).orElseThrow();r.setStatus(RentalRequestStatus.ACCEPTED);rentals.save(r);var c=contracts.findById(id).orElseThrow();c.setStatus(ContractStatus.DRAFT);c.setSignedAt(null);contracts.save(c);var l=properties.findById(listingId).orElseThrow();l.setStatus(ListingStatus.RESERVED);properties.save(l);});}

    RentalWorkflowView proposeCancel(){beforeLease();return cancellation.propose(rentalId,"owner",new RentalWorkflowPayloads.Cancellation(0L,"Không hoàn tất ký, đề nghị hoàn toàn bộ tiền",List.of()));}
    @Test void zeroValueConfirmedReceiptCancelsImmediatelyWithoutARefundPendingNotice(){
        // Covers the existing no-refund branch: a confirmed zero-value receipt has no money to return.
        beforeLease();
        tx(()->{
            var r=rentals.findById(rentalId).orElseThrow();r.setDepositAmount(BigDecimal.ZERO);rentals.save(r);
            var cash=paymentRepo.findByRentalRequestIdAndType(rentalId,PaymentType.RESERVATION_DEPOSIT).orElseThrow();
            cash.setTotalAmount(BigDecimal.ZERO);paymentRepo.save(cash);
            var ledger=deposits.findByRentalRequestId(rentalId).orElseThrow();
            ledger.setOriginalAmount(BigDecimal.ZERO);ledger.setHeldAmount(BigDecimal.ZERO);ledger.setRefundableAmount(BigDecimal.ZERO);deposits.save(ledger);
        });
        var proposed=cancellation.propose(rentalId,"owner",new RentalWorkflowPayloads.Cancellation(0L,"Thống nhất kết thúc hồ sơ chưa phát sinh tiền",List.of()));
        var done=cancellation.act(rentalId,"tenant","ACCEPT",action(proposed.revision()));
        assertEquals("COMPLETED",done.status());assertTrue(done.refundPayments().isEmpty());
        assertEquals(RentalRequestStatus.CANCELLED_BY_OWNER,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant","RENTAL_REQUEST_CANCELLED:OWNER:owner"),events());
        assertThrows(AppException.class,()->cancellation.act(rentalId,"tenant","ACCEPT",action(proposed.revision())));
        assertEquals(2,events().size());
    }
    @Test void fullRefundWorksWithoutAnyDepositDocumentAndKeepsReceiptIndependent(){
        var proposed=proposeCancel();assertEquals("PROPOSED",proposed.status());assertNotNull(rentals.findById(rentalId).orElseThrow().getResolutionWorkflowId());
        var done=cancellation.act(rentalId,"tenant","ACCEPT",action(proposed.revision()));
        assertEquals("REFUND_PENDING",done.status());assertEquals("REFUND_PENDING",done.depositObligation());assertEquals(1,done.refundPayments().size());
        assertEquals(PaymentStatus.AWAITING_TRANSFER,done.refundPayments().getFirst().status());assertEquals(0,principal.compareTo(done.refundPayments().getFirst().totalAmount()));
        assertEquals(DepositStatus.REFUND_DUE,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
        assertAwaitingRefund();
        assertThrows(AppException.class,()->cancellation.act(rentalId,"tenant","ACCEPT",action(proposed.revision())));
        String refund=done.refundPayments().getFirst().id();
        markRefundReported(refund);
        assertAwaitingRefund();
        assertThrows(AppException.class,()->payments.confirmReceipt(refund,"owner"));
        payments.confirmReceipt(refund,"tenant");
        assertEquals("REFUNDED",cancellation.get(rentalId,"tenant").depositObligation());
        assertEquals(0,principal.compareTo(ledger.forRental(rentalId).confirmedRefundedAmount()));
        assertEquals(RentalRequestStatus.CANCELLED_BY_OWNER,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(ContractStatus.CANCELLED,contracts.findById(id).orElseThrow().getStatus());
        verify(listings).releaseReserved(any(),eq("tenant"),anyString());
        payments.confirmReceipt(refund,"tenant");
        verify(parking,times(1)).releaseReservationsForRequest(rentalId);
    }
    private void assertAwaitingRefund(){
        assertEquals(RentalRequestStatus.ACCEPTED,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(ContractStatus.DRAFT,contracts.findById(id).orElseThrow().getStatus());
        assertEquals(ListingStatus.RESERVED,properties.findById(listingId).orElseThrow().getStatus());
        verify(parking,never()).releaseReservationsForRequest(rentalId);
        verify(listings,never()).releaseReserved(any(),anyString(),anyString());
    }
    private void markRefundReported(String id){
        tx(()->{var payment=paymentRepo.findById(id).orElseThrow();payment.setStatus(PaymentStatus.TRANSFER_REPORTED);paymentRepo.save(payment);});
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void signedDepositAutomaticallyEndsAfterTheFinalWindowWithTheCorrectMoneyAndFault(boolean unpaidRent) {
        beforeLease();clock("2026-11-11T10:00:00");String[] depositId={null};
        tx(()->{
            var r=rentals.findById(rentalId).orElseThrow();r.setMoveInDate(LocalDate.of(2026,11,10));r.setDepositAmount(new BigDecimal("1000000"));
            r.setReservationPolicyVersion(com.hs.common.payment.ReservationPolicy.VERSION);r.setReservationPolicyAcceptedAt(time.now().minusSeconds(86400));
            var cash=paymentRepo.findByRentalRequestIdAndType(rentalId,PaymentType.RESERVATION_DEPOSIT).orElseThrow();cash.setTotalAmount(new BigDecimal("1000000"));
            var deposit=deposits.findByRentalRequestId(rentalId).orElseThrow();deposit.setOriginalAmount(new BigDecimal("1000000"));deposit.setHeldAmount(new BigDecimal("1000000"));deposit.setRefundableAmount(new BigDecimal("1000000"));
            var dc=contracts.saveAndFlush(Contract.builder().contractNumber("DC-AUTO-"+UUID.randomUUID()).rentalRequestId(rentalId).listingId(listingId)
                    .landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("version").agreementKind("DEPOSIT")
                    .status(ContractStatus.ACTIVE).signedAt(time.now().minusSeconds(86400)).build());depositId[0]=dc.getId();
            var identity=new com.hs.contract.deposit.DepositAgreement();identity.setRentalRequestId(rentalId);identity.setSourcePaymentId(cash.getId());identity.setSigningContractId(dc.getId());
            identity.setSnapshotJson("{}");identity.setSnapshotHash(com.hs.common.payment.ReservationPolicy.hash("{}"));identity.setCapturedAt(time.now().minusSeconds(86400));depositAgreementRecords.saveAndFlush(identity);
            paymentRepo.saveAndFlush(PaymentRequest.builder().rentalRequestId(rentalId).listingId(listingId).contractId(unpaidRent?null:id).payerId("tenant").payeeId("owner")
                    .type(PaymentType.INITIAL).direction(PaymentDirection.TENANT_TO_LANDLORD).status(unpaidRent?PaymentStatus.AWAITING_TRANSFER:PaymentStatus.CONFIRMED)
                    .totalAmount(new BigDecimal("2000000")).dueAt(time.now().minusSeconds(1)).confirmedAt(unpaidRent?null:time.now().minusSeconds(7200))
                    .transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());
            if(unpaidRent){contracts.deleteById(id);contracts.flush();}
            else {
                var c=contracts.findById(id).orElseThrow();c.setStatus(ContractStatus.TENANT_SIGNATURE_PENDING);c.setTenantSigningDueAt(time.now().minusSeconds(1));
                signatures.saveAndFlush(SignatureRequest.builder().contractId(id).revisionId(c.getCurrentRevisionId()).sourceDocumentId("file").docId(UUID.randomUUID().toString())
                        .signerRole(SignerRole.LANDLORD).signerUserId("owner").status(SignatureRequestStatus.SIGNED).build());
                documents.saveAndFlush(ContractDocument.builder().contractId(id).revisionId(c.getCurrentRevisionId()).templateVersionId("version")
                        .documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.SIGNED_LANDLORD).status(DocumentGenerationStatus.READY).storageObjectId("file").build());
            }
        });
        var realDeposits=new com.hs.contract.deposit.DepositAgreementService(depositAgreementRecords,contracts,signatures,revisions,mock(ContractTemplateVersionRepository.class),rentals,onboardingEvents,deposits,paymentRepo,lines,contractDataBuilder,
                new com.hs.contract.service.engine.ContractFieldCatalog(),new ObjectMapper(),time,mock(com.hs.contract.notification.AgreementSigningNotificationWriter.class));
        org.springframework.test.util.ReflectionTestUtils.setField(onboarding,"depositAgreements",realDeposits);
        try {
            onboarding.processDue(rentalId);
            var noticed=rentals.findById(rentalId).orElseThrow();assertNotNull(noticed.getDefaultRespondBy());assertNull(noticed.getDefaultCompletedAt());
            assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
            time.instant=noticed.getDefaultRespondBy().minusSeconds(1);onboarding.processDue(rentalId);assertNull(rentals.findById(rentalId).orElseThrow().getDefaultCompletedAt());
            time.instant=noticed.getDefaultRespondBy();onboarding.processDue(rentalId);onboarding.processDue(rentalId);
            assertEquals(RentalRequestStatus.CANCELLED_BY_SYSTEM,rentals.findById(rentalId).orElseThrow().getStatus());
            var dc=contracts.findById(depositId[0]).orElseThrow();assertEquals(ContractStatus.TERMINATED,dc.getStatus());assertNotNull(dc.getSignedAt());assertNotNull(dc.getTerminationCompletedAt());
            assertEquals(DepositStatus.RETAINED_BY_LANDLORD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
            assertEquals(0,new BigDecimal("1000000").compareTo(deposits.findByRentalRequestId(rentalId).orElseThrow().getRetainedAmount()));
            assertTrue(onboardingEvents.findByRentalRequestIdOrderByOccurredAtDesc(rentalId).stream().anyMatch(e->"DEPOSIT_TERMINATED_TENANT_DEFAULT".equals(e.getAction())&&e.getNote().contains("Lỗi Bên B")));
            var refunds=paymentRepo.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.INITIAL_PAYMENT_REFUND));assertEquals(unpaidRent?0:1,refunds.size());
            var summary=onboarding.refresh(rentalId,"tenant").message();
            assertTrue(summary.contains("Chủ nhà giữ tiền cọc đã nhận."));
            assertEquals(unpaidRent,summary.contains("Không có tiền thuê cần hoàn."));
            assertEquals(!unpaidRent,summary.contains("khoản hoàn riêng"));
            assertFalse(summary.contains("sẽ tự chấm dứt"));
            if(!unpaidRent)assertEquals(0,new BigDecimal("2000000").compareTo(refunds.getFirst().getTotalAmount()));
            assertEquals(unpaidRent?PaymentStatus.CANCELLED:PaymentStatus.CONFIRMED,paymentRepo.findByRentalRequestIdAndType(rentalId,PaymentType.INITIAL).orElseThrow().getStatus());
            verify(parking,times(1)).releaseReservationsForRequest(rentalId);verify(listings,times(1)).releaseReserved(any(),eq("SYSTEM"),anyString());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(onboarding,"depositAgreements",depositAgreements);}
    }
    @ParameterizedTest @CsvSource({"2026-11-06T10:00:00,false","2026-11-06T10:00:00,true","2026-11-10T10:00:00,false","2026-11-10T10:00:00,true"})
    void novemberDepositOnlyRefundDoesNotRequireALeaseOrAnInitialRentReceipt(String current,boolean depositSigned) {
        beforeLease();clock(current);String[] depositContract={null};
        tx(()->{
            contracts.deleteById(id);contracts.flush();
            var rental=rentals.findById(rentalId).orElseThrow();rental.setMoveInDate(LocalDate.of(2026,11,10));rental.setDepositAmount(new BigDecimal("1000000"));
            var paid=paymentRepo.findByRentalRequestIdAndType(rentalId,PaymentType.RESERVATION_DEPOSIT).orElseThrow();
            paid.setContractId(null);paid.setTotalAmount(new BigDecimal("1000000"));
            paid.setConfirmedAt(LocalDateTime.parse("2026-11-06T09:00:00").atZone(BillingTime.ZONE).toInstant());
            lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(paid.getId()).type("DEPOSIT").displayName("Tiền cọc giữ chỗ")
                    .amount(new BigDecimal("1000000")).quantity(1).unitPrice(new BigDecimal("1000000")).build());
            var record=deposits.findByRentalRequestId(rentalId).orElseThrow();record.setContractId(null);
            record.setOriginalAmount(new BigDecimal("1000000"));record.setHeldAmount(new BigDecimal("1000000"));record.setRefundableAmount(new BigDecimal("1000000"));
            var dc=contracts.saveAndFlush(Contract.builder().contractNumber("DC-NOV-"+UUID.randomUUID()).rentalRequestId(rentalId).listingId(listingId)
                    .landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("version").agreementKind("DEPOSIT")
                    .status(depositSigned?ContractStatus.ACTIVE:ContractStatus.DRAFT).signedAt(depositSigned?paid.getConfirmedAt():null).build());
            depositContract[0]=dc.getId();when(depositAgreements.find(rentalId)).thenReturn(Optional.of(dc));
            if(!time.today().isBefore(rental.getMoveInDate()))paymentRepo.saveAndFlush(PaymentRequest.builder().rentalRequestId(rentalId).listingId(listingId)
                    .payerId("tenant").payeeId("owner").type(PaymentType.INITIAL).direction(PaymentDirection.TENANT_TO_LANDLORD)
                    .status(PaymentStatus.AWAITING_TRANSFER).totalAmount(new BigDecimal("2000000")).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());
        });
        assertTrue(contracts.findByRentalRequestId(rentalId).isEmpty());
        if(depositSigned) {
            when(depositAgreements.tenantHasSigned(rentalId)).thenReturn(true);
            assertFalse(onboarding.refresh(rentalId,"tenant").canRequestCancellation());
            assertThrows(AppException.class,()->onboarding.requestCancellation(rentalId,"tenant",new RentalOnboardingService.Cancellation("Đề nghị hoàn cọc sau khi đã ký")));
            assertThrows(AppException.class,()->cancellation.propose(rentalId,"tenant",new RentalWorkflowPayloads.Cancellation(0L,"Đề nghị hoàn cọc sau khi đã ký",List.of())));
            assertTrue(paymentRepo.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.INITIAL_PAYMENT_REFUND)).isEmpty());
            assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());return;
        }
        var proposed=cancellation.propose(rentalId,"tenant",new RentalWorkflowPayloads.Cancellation(0L,"Chưa đóng tháng đầu, hai bên thống nhất hoàn cọc",List.of()));
        var pending=cancellation.act(rentalId,"owner","ACCEPT",action(proposed.revision()));
        assertEquals(1,pending.refundPayments().size());var refund=pending.refundPayments().getFirst();
        assertEquals(0,new BigDecimal("1000000").compareTo(refund.totalAmount()));
        assertEquals(1,refund.lineItems().size());assertEquals("DEPOSIT_REFUND",refund.lineItems().getFirst().type());
        assertEquals(ListingStatus.RESERVED,properties.findById(listingId).orElseThrow().getStatus());
        verify(listings,never()).releaseReserved(any(),any(),any());
        paymentRepo.findByRentalRequestIdAndType(rentalId,PaymentType.INITIAL).ifPresent(rent->assertEquals(PaymentStatus.CANCELLED,rent.getStatus()));
        markRefundReported(refund.id());assertThrows(AppException.class,()->payments.confirmReceipt(refund.id(),"owner"));
        payments.confirmReceipt(refund.id(),"tenant");
        assertEquals(RentalRequestStatus.CANCELLED_BY_RENTER,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(DepositStatus.REFUNDED,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
        assertEquals(0,new BigDecimal("1000000").compareTo(ledger.forRental(rentalId).confirmedRefundedAmount()));
        assertEquals(depositSigned?ContractStatus.ACTIVE:ContractStatus.CANCELLED,contracts.findById(depositContract[0]).orElseThrow().getStatus());
        assertTrue(contracts.findByRentalRequestId(rentalId).isEmpty());
        verify(listings).releaseReserved(any(),eq("tenant"),anyString());
    }

    @Test void oneMillionRentWithoutDepositHasAnExactRefundBreakdownAndRequiresTenantReceipt() {
        beforeLease();
        tx(()->{
            var rental=rentals.findById(rentalId).orElseThrow();rental.setDepositAmount(BigDecimal.ZERO);
            var cash=paymentRepo.findByRentalRequestIdAndType(rentalId,PaymentType.RESERVATION_DEPOSIT).orElseThrow();
            cash.setType(PaymentType.INITIAL);cash.setTotalAmount(new BigDecimal("1000000"));
            lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(cash.getId()).type("RENT").displayName("Tiền thuê kỳ đầu")
                    .amount(new BigDecimal("1000000")).quantity(1).unitPrice(new BigDecimal("1000000")).build());
            var record=deposits.findByRentalRequestId(rentalId).orElseThrow();
            record.setOriginalAmount(BigDecimal.ZERO);record.setHeldAmount(BigDecimal.ZERO);record.setRefundableAmount(BigDecimal.ZERO);
        });
        onboarding.requestCancellation(rentalId,"tenant",new RentalOnboardingService.Cancellation("Hai bên đồng ý hủy và hoàn tháng đầu"));
        var pending=onboarding.decideCancellation(rentalId,"owner",new RentalOnboardingService.Decision(true,null));
        var refund=pending.payments().stream().filter(p->p.type()==PaymentType.INITIAL_PAYMENT_REFUND).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("1000000").compareTo(refund.totalAmount()));
        assertEquals("owner",refund.payerId());assertEquals("tenant",refund.payeeId());
        assertEquals(1,refund.lineItems().size());assertEquals("RENT_REFUND",refund.lineItems().getFirst().type());
        assertEquals(0,refund.totalAmount().compareTo(refund.lineItems().getFirst().amount()));
        assertNull(deposits.findByRentalRequestId(rentalId).orElseThrow().getRefundPaymentRequestId());
        assertAwaitingRefund();markRefundReported(refund.id());assertAwaitingRefund();
        assertThrows(AppException.class,()->payments.confirmReceipt(refund.id(),"owner"));
        payments.confirmReceipt(refund.id(),"tenant");
        assertEquals(RentalRequestStatus.CANCELLED_BY_RENTER,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(0,ledger.forRental(rentalId).confirmedReceivedAmount().signum());
        assertEquals(0,ledger.forRental(rentalId).confirmedRefundedAmount().signum());
        verify(listings).releaseReserved(any(),eq("tenant"),anyString());
    }

    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void oneRefundCoversThreeMillionWhetherPaidTogetherOrSeparately(boolean workflow,boolean combined) {
        beforeLease();clock("2026-11-10T10:00:00");
        var originalAmounts=new HashMap<String,BigDecimal>();
        tx(()->{
            var rental=rentals.findById(rentalId).orElseThrow();rental.setDepositAmount(new BigDecimal("1000000"));rental.setMoveInDate(LocalDate.of(2026,11,10));
            var paid=paymentRepo.findByRentalRequestIdAndType(rentalId,PaymentType.RESERVATION_DEPOSIT).orElseThrow();
            paid.setType(combined?PaymentType.INITIAL:PaymentType.RESERVATION_DEPOSIT);paid.setTotalAmount(new BigDecimal(combined?"3000000":"1000000"));
            paid.setConfirmedAt(LocalDateTime.parse("2026-11-06T10:00:00").atZone(BillingTime.ZONE).toInstant());
            var deposit=deposits.findByRentalRequestId(rentalId).orElseThrow();deposit.setOriginalAmount(new BigDecimal("1000000"));deposit.setHeldAmount(new BigDecimal("1000000"));deposit.setRefundableAmount(new BigDecimal("1000000"));
            lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(paid.getId()).type("DEPOSIT").displayName("Tiền đặt cọc").amount(new BigDecimal("1000000")).build());
            var rent=combined?paid:paymentRepo.saveAndFlush(PaymentRequest.builder().rentalRequestId(rentalId).listingId(listingId).contractId(id)
                    .payerId("tenant").payeeId("owner").type(PaymentType.INITIAL).direction(PaymentDirection.TENANT_TO_LANDLORD)
                    .status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("2000000")).confirmedAt(time.now())
                    .transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());
            lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(rent.getId()).type("RENT").displayName("Tiền thuê kỳ đầu").amount(new BigDecimal("2000000")).build());
            originalAmounts.put(paid.getId(),paid.getTotalAmount());originalAmounts.put(rent.getId(),rent.getTotalAmount());
        });
        List<PaymentRequestResponse> refunds;
        if(workflow) {
            var proposed=cancellation.propose(rentalId,"tenant",new RentalWorkflowPayloads.Cancellation(0L,"Hai bên thống nhất hủy và hoàn toàn bộ",List.of()));
            refunds=cancellation.act(rentalId,"owner","ACCEPT",action(proposed.revision())).refundPayments();
        } else {
            onboarding.requestCancellation(rentalId,"tenant",new RentalOnboardingService.Cancellation("Hai bên thống nhất hủy và hoàn toàn bộ"));
            refunds=onboarding.decideCancellation(rentalId,"owner",new RentalOnboardingService.Decision(true,null)).payments().stream().filter(p->p.type()==PaymentType.INITIAL_PAYMENT_REFUND).toList();
        }
        assertEquals(1,refunds.size());var refund=refunds.getFirst();
        assertEquals(0,new BigDecimal("3000000").compareTo(refund.totalAmount()));assertEquals(2,refund.lineItems().size());
        assertEquals(0,new BigDecimal("1000000").compareTo(refund.lineItems().stream().filter(l->l.type().equals("DEPOSIT_REFUND")).findFirst().orElseThrow().amount()));
        assertEquals(0,new BigDecimal("2000000").compareTo(refund.lineItems().stream().filter(l->l.type().equals("RENT_REFUND")).findFirst().orElseThrow().amount()));
        assertEquals(refund.id(),deposits.findByRentalRequestId(rentalId).orElseThrow().getRefundPaymentRequestId());
        assertAwaitingRefund();assertThrows(AppException.class,()->payments.confirmReceipt(refund.id(),"tenant"));
        markRefundReported(refund.id());assertAwaitingRefund();assertThrows(AppException.class,()->payments.confirmReceipt(refund.id(),"owner"));
        payments.confirmReceipt(refund.id(),"tenant");payments.confirmReceipt(refund.id(),"tenant");
        assertEquals(RentalRequestStatus.CANCELLED_BY_RENTER,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(0,new BigDecimal("1000000").compareTo(ledger.forRental(rentalId).confirmedRefundedAmount()));
        originalAmounts.forEach((payment,amount)->{var source=paymentRepo.findById(payment).orElseThrow();assertEquals(PaymentStatus.CONFIRMED,source.getStatus());assertEquals(0,amount.compareTo(source.getTotalAmount()));});
        assertEquals(1,paymentRepo.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.INITIAL_PAYMENT_REFUND)).size());
        verify(parking,times(1)).releaseReservationsForRequest(rentalId);verify(listings,times(1)).releaseReserved(any(),eq("tenant"),anyString());
    }

    @Test void combinedInitialRefundAllocatesRentAndDepositButDepositLedgerCountsOnlyDeposit() {
        beforeLease();BigDecimal rent=new BigDecimal("1000000"),total=rent.add(principal);
        tx(()->{
            var cash=paymentRepo.findByRentalRequestIdAndType(rentalId,PaymentType.RESERVATION_DEPOSIT).orElseThrow();
            cash.setType(PaymentType.INITIAL);cash.setTotalAmount(total);
            lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(cash.getId()).type("RENT").displayName("Tiền thuê kỳ đầu")
                    .amount(rent).quantity(1).unitPrice(rent).build());
            lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(cash.getId()).type("DEPOSIT").displayName("Tiền đặt cọc")
                    .amount(principal).quantity(1).unitPrice(principal).sortOrder(1).build());
        });
        var proposed=cancellation.propose(rentalId,"owner",new RentalWorkflowPayloads.Cancellation(0L,"Hai bên hủy trước thuê, hoàn đủ tiền đã nhận",List.of()));
        var accepted=cancellation.act(rentalId,"tenant","ACCEPT",action(proposed.revision()));
        var refund=accepted.refundPayments().getFirst();assertEquals(0,total.compareTo(refund.totalAmount()));
        assertEquals(2,refund.lineItems().size());
        assertEquals(0,total.compareTo(refund.lineItems().stream().map(com.hs.payment.dto.PaymentLineItemResponse::amount).reduce(BigDecimal.ZERO,BigDecimal::add)));
        assertAwaitingRefund();markRefundReported(refund.id());payments.confirmReceipt(refund.id(),"tenant");
        assertEquals(0,principal.compareTo(ledger.forRental(rentalId).confirmedRefundedAmount()));
        assertEquals(0,principal.compareTo(ledger.forRental(rentalId).confirmedReceivedAmount()));
    }

    @Test void legacyTenantCancellationRefundsRentWithoutInventingDeposit(){
        beforeLease();
        tx(()->{
            var r=rentals.findById(rentalId).orElseThrow();r.setDepositAmount(BigDecimal.ZERO);
            var cash=paymentRepo.findByRentalRequestIdAndType(rentalId,PaymentType.RESERVATION_DEPOSIT).orElseThrow();
            cash.setType(PaymentType.INITIAL);cash.setTotalAmount(new BigDecimal("3400000"));
            var ledger=deposits.findByRentalRequestId(rentalId).orElseThrow();
            ledger.setOriginalAmount(BigDecimal.ZERO);ledger.setHeldAmount(BigDecimal.ZERO);ledger.setRefundableAmount(BigDecimal.ZERO);
        });
        onboarding.requestCancellation(rentalId,"tenant",new RentalOnboardingService.Cancellation("Hai bên đồng ý hoàn tiền thuê tháng đầu"));
        var pending=onboarding.decideCancellation(rentalId,"owner",new RentalOnboardingService.Decision(true,null));
        assertEquals("CANCELLATION_REFUND_PENDING",pending.stage());assertFalse(pending.canSign());
        assertAwaitingRefund();
        var refund=pending.payments().stream().filter(p->p.type()==PaymentType.INITIAL_PAYMENT_REFUND).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("3400000").compareTo(refund.totalAmount()));
        assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
        markRefundReported(refund.id());payments.confirmReceipt(refund.id(),"tenant");
        assertEquals(RentalRequestStatus.CANCELLED_BY_RENTER,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(ContractStatus.CANCELLED,contracts.findById(id).orElseThrow().getStatus());
        verify(listings).releaseReserved(any(),eq("tenant"),anyString());
    }
    @Test void cancelAfterResponseBoundaryRequiresExplicitContactAndNoSignatureAmbiguity(){
        var p=proposeCancel();assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","FULL_REFUND_EXIT",action(p.revision())));
        time.instant=p.respondBy().minusNanos(1);assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","FULL_REFUND_EXIT",action(p.revision())));
        time.instant=p.respondBy();assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","FULL_REFUND_EXIT",new RentalWorkflowPayloads.Action(p.revision(),"Đã kiểm tra phòng chưa bàn giao cho khách",true,false)));
        assertEquals("REFUND_PENDING",cancellation.act(rentalId,"owner","FULL_REFUND_EXIT",action(p.revision())).status());
        assertAwaitingRefund();
    }
    @Test void cancelSelfAcceptanceStaleAndOutsiderFailWithoutChangingFunds(){
        var p=proposeCancel();assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","ACCEPT",action(p.revision())));
        assertThrows(AppException.class,()->cancellation.act(rentalId,"other","ACCEPT",action(p.revision())));
        assertThrows(AppException.class,()->cancellation.act(rentalId,"tenant","ACCEPT",action(0)));
        assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=SignatureRequestStatus.class,names={"CREATED","PENDING_USER_CONFIRMATION","PROVIDER_SIGNED","EMBEDDING","FAILED"})
    void unknownProviderOutcomeNeverReleasesRoom(SignatureRequestStatus state){
        var p=proposeCancel();tx(()->signatures.saveAndFlush(SignatureRequest.builder().contractId(id).revisionId(contracts.findById(id).orElseThrow().getCurrentRevisionId()).sourceDocumentId("file").docId(UUID.randomUUID().toString()).signerRole(SignerRole.TENANT).signerUserId("tenant").status(state).build()));
        assertThrows(AppException.class,()->cancellation.act(rentalId,"tenant","ACCEPT",action(p.revision())));
        assertEquals(RentalRequestStatus.ACCEPTED,rentals.findById(rentalId).orElseThrow().getStatus());verify(parking,never()).releaseReservationsForRequest(anyString());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=PaymentStatus.class,names={"TRANSFER_REPORTED","DISPUTED","REJECTED"})
    void ambiguousRentTransferBlocksCancellation(PaymentStatus state){
        var p=proposeCancel();tx(()->paymentRepo.saveAndFlush(PaymentRequest.builder().contractId(id).rentalRequestId(rentalId).listingId(listingId).payerId("tenant").payeeId("owner").type(PaymentType.INITIAL).direction(PaymentDirection.TENANT_TO_LANDLORD).status(state).totalAmount(new BigDecimal("5000000")).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build()));
        assertThrows(AppException.class,()->cancellation.act(rentalId,"tenant","ACCEPT",action(p.revision())));
        assertTrue(paymentRepo.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.INITIAL_PAYMENT_REFUND)).isEmpty());
    }
    @Test void unsignedDepositAndSeparateRentAndDepositReceiptsHaveOneRefund(){
        var p=proposeCancel();tx(()->{var dc=contracts.saveAndFlush(Contract.builder().contractNumber("DC-"+UUID.randomUUID()).rentalRequestId(rentalId).listingId(listingId).landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("version").agreementKind("DEPOSIT").status(ContractStatus.DRAFT).build());when(depositAgreements.find(rentalId)).thenReturn(Optional.of(dc));
            paymentRepo.saveAndFlush(PaymentRequest.builder().contractId(id).rentalRequestId(rentalId).listingId(listingId).payerId("tenant").payeeId("owner").type(PaymentType.INITIAL).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("5000000")).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).confirmedAt(time.now()).build());});
        var done=cancellation.act(rentalId,"tenant","ACCEPT",action(p.revision()));assertEquals(1,done.refundPayments().size());assertEquals(ContractStatus.DRAFT,contracts.findById(depositAgreements.find(rentalId).orElseThrow().getId()).orElseThrow().getStatus());
        assertEquals(0,new BigDecimal("25000000").compareTo(done.refundPayments().getFirst().totalAmount()));
        String refund=done.refundPayments().getFirst().id();markRefundReported(refund);assertAwaitingRefund();payments.confirmReceipt(refund,"tenant");
        assertEquals(0,principal.compareTo(ledger.forRental(rentalId).confirmedRefundedAmount()));
        assertEquals(ContractStatus.CANCELLED,contracts.findById(id).orElseThrow().getStatus());
        assertEquals(ContractStatus.CANCELLED,contracts.findById(depositAgreements.find(rentalId).orElseThrow().getId()).orElseThrow().getStatus());
        verify(parking,times(1)).releaseReservationsForRequest(rentalId);
    }
    @Test void cancellationEscalatesToExistingCaseAndKeepsSigningPaused(){var p=proposeCancel();var next=cancellation.act(rentalId,"tenant","ESCALATE",action(p.revision()));assertEquals("DISPUTED",next.status());assertNotNull(next.complaintId());assertNotNull(rentals.findById(rentalId).orElseThrow().getResolutionWorkflowId());assertThrows(AppException.class,()->cancellation.propose(rentalId,"owner",new RentalWorkflowPayloads.Cancellation(next.revision(),"Đề xuất khác trong khi có khiếu nại",List.of())));}
    @Test void withdrawalUnpausesSigningAndPreservesPriorProposal(){var p=proposeCancel();var done=cancellation.act(rentalId,"owner","WITHDRAW",action(p.revision()));assertEquals("WITHDRAWN",done.status());assertNull(rentals.findById(rentalId).orElseThrow().getResolutionWorkflowId());assertEquals(2,done.history().size());}

    @Test void proposeAcceptRefundReceiptWriteOnePendingThenExactlyOneFinalCancelEvent(){
        var p=proposeCancel();
        assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant"),events());
        var done=cancellation.act(rentalId,"tenant","ACCEPT",action(p.revision()));
        // Refund is still owed: the request is ACCEPTED, so the notice must say pending, never cancelled.
        assertEquals(RentalRequestStatus.ACCEPTED,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant","RENTAL_CANCELLATION_REFUND_PENDING:OWNER:owner"),events());
        String refund=done.refundPayments().getFirst().id();
        markRefundReported(refund);
        assertEquals(2,events().size()); // a reported (not confirmed) transfer is not a cancellation
        payments.confirmReceipt(refund,"tenant");
        assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant","RENTAL_CANCELLATION_REFUND_PENDING:OWNER:owner",
            "RENTAL_REQUEST_CANCELLED:OWNER:owner"),events());
        payments.confirmReceipt(refund,"tenant"); // replayed receipt
        assertEquals(3,events().size());
    }
    @Test void failedReleaseRollsBackRefundConfirmationAndTheFinalCancelNotice(){
        var p=proposeCancel();var pending=cancellation.act(rentalId,"tenant","ACCEPT",action(p.revision()));
        String refund=pending.refundPayments().getFirst().id();markRefundReported(refund);
        doThrow(new IllegalStateException("release failure")).when(listings).releaseReserved(any(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->payments.confirmReceipt(refund,"tenant"));
        assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant","RENTAL_CANCELLATION_REFUND_PENDING:OWNER:owner"),events());
        assertEquals(PaymentStatus.TRANSFER_REPORTED,paymentRepo.findById(refund).orElseThrow().getStatus());
        reset(listings);
        payments.confirmReceipt(refund,"tenant"); // retry after the failure commits the one final notice
        assertEquals("RENTAL_REQUEST_CANCELLED:OWNER:owner",events().getLast());assertEquals(3,events().size());
    }
    @Test void withdrawRejectAndEscalateNotifyTheCounterpart(){
        var p=proposeCancel();cancellation.act(rentalId,"owner","WITHDRAW",action(p.revision()));
        assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant","RENTAL_CANCELLATION_WITHDRAWN:RENTER:tenant"),events());
    }
    @Test void counterpartRejectIsReportedToTheProposer(){
        var p=proposeCancel();cancellation.act(rentalId,"tenant","REJECT",action(p.revision()));
        assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant","RENTAL_CANCELLATION_REJECTED:OWNER:owner"),events());
    }
    @Test void escalationIsReportedToTheProposerWithoutAnAdminFeed(){
        var p=proposeCancel();cancellation.act(rentalId,"tenant","ESCALATE",action(p.revision()));
        assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant","RENTAL_CANCELLATION_DISPUTED:OWNER:owner"),events());
    }
    @Test void deniedOrStaleWorkflowActionsWriteNothing(){
        var p=proposeCancel();
        assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","ACCEPT",action(p.revision()))); // proposer cannot accept own proposal
        assertThrows(AppException.class,()->cancellation.act(rentalId,"outsider","REJECT",action(p.revision())));
        assertThrows(AppException.class,()->cancellation.act(rentalId,"tenant","ACCEPT",action(p.revision()+99)));
        assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant"),events());
    }
    @Test void resumeRequestAndConfirmNotifyTheCounterpartAfterSuccessfulSave(){
        var w=resolvedCancellation();var requested=cancellation.act(rentalId,"owner","REQUEST_RESUME",action(w.revision()));
        cancellation.act(rentalId,"tenant","RESUME",action(requested.revision()));
        var tail=events().subList(2,events().size());
        assertEquals(List.of("RENTAL_RESUME_REQUESTED:RENTER:tenant","RENTAL_RESUMED:OWNER:owner"),tail);
    }
    @Test void resumeDeclineAndWithdrawNotifyTheCounterpart(){
        var w=resolvedCancellation();var requested=cancellation.act(rentalId,"owner","REQUEST_RESUME",action(w.revision()));
        cancellation.act(rentalId,"tenant","DECLINE_RESUME",action(requested.revision()));
        assertEquals("RENTAL_RESUME_REJECTED:OWNER:owner",events().getLast());
    }
    @Test void resumeWithdrawIsReportedToTheOtherParty(){
        var w=resolvedCancellation();var requested=cancellation.act(rentalId,"owner","REQUEST_RESUME",action(w.revision()));
        cancellation.act(rentalId,"owner","WITHDRAW_RESUME",action(requested.revision()));
        assertEquals("RENTAL_RESUME_WITHDRAWN:RENTER:tenant",events().getLast());
    }
    @Test void invalidResumeAttemptsWriteNoResumeNotice(){
        var w=proposeCancel();w=cancellation.act(rentalId,"tenant","ESCALATE",action(w.revision()));var open=w;
        assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","REQUEST_RESUME",action(open.revision())));
        assertTrue(events().stream().noneMatch(e->e.contains("RESUME")));
    }
    @Test void releaseFailureRollsBackRefundConfirmationAndCancellation(){
        var proposal=proposeCancel();
        var pending=cancellation.act(rentalId,"tenant","ACCEPT",action(proposal.revision()));
        String refund=pending.refundPayments().getFirst().id();markRefundReported(refund);
        doThrow(new IllegalStateException("release failure")).when(listings).releaseReserved(any(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->payments.confirmReceipt(refund,"tenant"));
        assertEquals(PaymentStatus.TRANSFER_REPORTED,paymentRepo.findById(refund).orElseThrow().getStatus());
        assertEquals("REFUND_PENDING",workflows.findById(rentalId+":CANCELLATION").orElseThrow().getStatus());
        assertEquals(RentalRequestStatus.ACCEPTED,rentals.findById(rentalId).orElseThrow().getStatus());
        assertEquals(DepositStatus.REFUND_DUE,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
    }
    @Test void twoPartyMoveInConfirmationAndCorrectionDoNotRewriteSignedMeters(){
        var original=revisions.findById(contracts.findById(id).orElseThrow().getCurrentRevisionId()).orElseThrow().getInitialMetersSnapshot();
        var p=handover.propose(rentalId,"owner",move(0));var one=handover.act(rentalId,"tenant","CONFIRM",action(p.revision()));assertEquals("PROPOSED",one.status());assertNotNull(one.tenantConfirmedAt());assertNull(one.ownerConfirmedAt());
        assertThrows(AppException.class,()->handover.act(rentalId,"owner","CONFIRM",action(p.revision())));
        var done=handover.act(rentalId,"owner","CONFIRM",action(one.revision()));assertEquals("COMPLETED",done.status());assertEquals("TRANSFERRED_TO_LEASE",done.depositObligation());
        var revised=handover.propose(rentalId,"owner",move(done.revision()));assertNull(revised.ownerConfirmedAt());assertNull(revised.tenantConfirmedAt());assertEquals(4,revised.history().size());
        assertEquals(original,revisions.findById(contracts.findById(id).orElseThrow().getCurrentRevisionId()).orElseThrow().getInitialMetersSnapshot());verify(parking,never()).releaseReservationsForContract(anyString());
    }
    @Test void handoverCannotBeInventedInFutureOrBeforeLeaseOrByTenant(){
        assertThrows(AppException.class,()->handover.propose(rentalId,"tenant",move(0)));
        var p=move(0);assertThrows(AppException.class,()->handover.propose(rentalId,"owner",new RentalWorkflowPayloads.Handover(0L,time.now().plusSeconds(1),2,p.condition(),p.reason(),p.electricity(),p.water(),p.inventory(),List.of())));
        assertThrows(AppException.class,()->handover.propose(rentalId,"owner",new RentalWorkflowPayloads.Handover(0L,Instant.parse("2025-01-01T00:00:00Z"),2,p.condition(),p.reason(),p.electricity(),p.water(),p.inventory(),List.of())));
        assertEquals("EMPTY",handover.get(rentalId,"tenant").status());
    }
    @Test void handoverRefusalCreatesCaseWithoutChangingLeaseOrFinancials(){var p=handover.propose(rentalId,"owner",move(0));var issue=handover.act(rentalId,"tenant","REPORT_ISSUE",action(p.revision()));assertEquals("DISPUTED",issue.status());assertNotNull(issue.complaintId());assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());}
    @Test void unsignedLeaseCannotHaveCompletedMoveIn(){beforeLease();assertThrows(AppException.class,()->handover.propose(rentalId,"owner",move(0)));}
    @Test void revisedLeaseSourceNeedsNewHandoverReview(){var p=handover.propose(rentalId,"owner",move(0));tx(()->{var c=contracts.findById(id).orElseThrow();c.setCurrentRevisionId("changed-revision");contracts.save(c);});assertThrows(AppException.class,()->handover.act(rentalId,"tenant","CONFIRM",action(p.revision())));}
    @Test void earlyExitDoesNotRequireDebtAndWaitsForTwoPhysicalReturnsAndExistingSettlement(){
        var p=termination.propose(rentalId,"tenant",early(0));assertNull(contracts.findById(id).orElseThrow().getAgreedEarlyEndExclusive());
        assertEquals(List.of("TERMINATION_RESPONSE_NEEDED:owner"),terminationEvents());
        assertThrows(AppException.class,()->termination.act(rentalId,"tenant","ACCEPT",action(p.revision())));
        var agreed=termination.act(rentalId,"owner","ACCEPT",action(p.revision()));assertEquals("AGREED",agreed.status());
        assertEquals(List.of("TERMINATION_RESPONSE_NEEDED:owner","TERMINATION_AGREED:tenant"),terminationEvents());
        var c=contracts.findById(id).orElseThrow();assertEquals(LocalDate.of(2026,12,20),c.getAgreedEarlyEndExclusive());assertEquals(3,c.getAgreedEarlyBillingMonths());assertNull(c.getEffectiveLeaseMonths());assertEquals(3,rentals.findById(rentalId).orElseThrow().getLeaseMonths());
        verify(parking,never()).releaseReservationsForContract(id);
        assertThrows(AppException.class,()->termination.act(rentalId,"tenant","CONFIRM_RETURN",action(agreed.revision())));
        clock("2026-12-20T10:00:00");service.get(id,"owner");assertEquals(ContractStatus.EXPIRED,contracts.findById(id).orElseThrow().getStatus());
        service.get(id,"tenant");assertEquals(List.of("TERMINATION_RESPONSE_NEEDED:owner","TERMINATION_AGREED:tenant","LEASE_EXPIRED:owner","LEASE_EXPIRED:tenant"),terminationEvents());
        var close=new ClosurePayload(null,null,BigDecimal.ZERO,"Trả nhà và hoàn toàn bộ tiền cọc",true);
        assertThrows(AppException.class,()->service.proposeClosure(id,"owner",close));
        var first=termination.act(rentalId,"tenant","CONFIRM_RETURN",action(agreed.revision()));assertEquals("AGREED",first.status());
        assertThrows(AppException.class,()->termination.act(rentalId,"owner","CONFIRM_RETURN",action(agreed.revision())));
        var returned=termination.act(rentalId,"owner","CONFIRM_RETURN",action(first.revision()));assertEquals("RETURNED",returned.status());verify(parking,never()).releaseReservationsForContract(id);
        assertThrows(AppException.class,()->service.proposeClosure(id,"owner",close));
        tx(()->{var bill=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();bill.setStatus(MonthlyInvoiceStatus.PAID);invoices.save(bill);});
        var closure=service.proposeClosure(id,"owner",close).closure();assertEquals(0,principal.compareTo(closure.getRefundAmount()));
        var accepted=service.acceptClosure(id,"tenant",new AcceptClosurePayload(closure.getProposalVersion(),true,true));assertEquals("REFUND_PENDING",accepted.closure().getStatus());verify(parking).releaseReservationsForContract(id);assertEquals(0,deposits.findByRentalRequestId(rentalId).orElseThrow().getRetainedAmount().signum());
        String refund=accepted.refundPayment().id();tx(()->{var cash=paymentRepo.findById(refund).orElseThrow();cash.setStatus(PaymentStatus.TRANSFER_REPORTED);paymentRepo.save(cash);});
        assertThrows(AppException.class,()->payments.confirmReceipt(refund,"owner"));payments.confirmReceipt(refund,"tenant");
        var closed=service.get(id,"tenant");assertEquals("CLOSED",closed.closure().getStatus());assertEquals(ContractStatus.CLOSED,contracts.findById(id).orElseThrow().getStatus());assertEquals(DepositStatus.REFUNDED,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
    }
    @Test void earlyCounterProposalAndRejectKeepOriginalLeaseAndAudit(){var p=termination.propose(rentalId,"tenant",early(0));var counter=termination.propose(rentalId,"owner",early(p.revision()));assertThrows(AppException.class,()->termination.act(rentalId,"owner","ACCEPT",action(p.revision())));var no=termination.act(rentalId,"tenant","REJECT",action(counter.revision()));assertEquals("REJECTED",no.status());assertNull(contracts.findById(id).orElseThrow().getMutualTerminationWorkflowId());assertNull(contracts.findById(id).orElseThrow().getAgreedEarlyEndExclusive());assertEquals(3,no.history().size());}
    @Test void pendingRenewalBlocksEarlyExitRatherThanCancellingSignedWork(){tx(()->renewals.saveAndFlush(ContractRenewal.builder().contractId(id).months(1).startsOn(LocalDate.of(2027,1,12)).endExclusive(LocalDate.of(2027,2,12)).status("APPROVED").requestedAt(time.now()).respondBy(time.now().plusSeconds(1000)).signBy(time.now().plusSeconds(2000)).note("Yêu cầu kiểm thử gia hạn").build()));assertThrows(AppException.class,()->termination.propose(rentalId,"tenant",early(0)));}
    @Test void issuedBillCrossingCutoffCannotBeSilentlyRewritten(){tx(()->{var b=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();b.setStatus(MonthlyInvoiceStatus.PAID);invoices.save(b);});assertThrows(AppException.class,()->termination.propose(rentalId,"tenant",early(0)));assertNull(contracts.findById(id).orElseThrow().getAgreedEarlyEndExclusive());}
    @Test void prepaidRentRefundUsesActualRentSourceAndNeverTouchesDepositLedger(){
        String[] source={null};tx(()->{var cash=paymentRepo.saveAndFlush(PaymentRequest.builder().contractId(id).rentalRequestId(rentalId).listingId(listingId).payerId("tenant").payeeId("owner").type(PaymentType.MONTHLY_RENT).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("6000000")).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).confirmedAt(time.now()).build());source[0]=cash.getId();lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(cash.getId()).type("RENT").displayName("Tiền thuê trả trước").amount(new BigDecimal("5000000")).build());});
        var input=early(0);var plan=new RentalWorkflowPayloads.Termination(0L,input.endExclusive(),input.reason(),input.handoverTerms(),input.finalRentPolicy(),List.of(new RentalWorkflowPayloads.RentRefund(source[0],new BigDecimal("1000000"))),List.of());
        var proposed=termination.propose(rentalId,"tenant",plan);assertTrue(paymentRepo.findByOnboardingKey("REFUND:"+source[0]).isEmpty());
        var agreed=termination.act(rentalId,"owner","ACCEPT",action(proposed.revision()));assertEquals(1,agreed.refundPayments().size());assertEquals(PaymentStatus.AWAITING_TRANSFER,agreed.refundPayments().getFirst().status());assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());assertEquals(0,principal.compareTo(deposits.findByRentalRequestId(rentalId).orElseThrow().getHeldAmount()));
        assertEquals(PaymentStatus.CONFIRMED,paymentRepo.findById(source[0]).orElseThrow().getStatus());
    }
    @Test void foreignOrDepositPaymentCannotBeUsedAsRentRefundSource(){String deposit=deposits.findByRentalRequestId(rentalId).orElseThrow().getInitialPaymentRequestId();var input=early(0);assertThrows(AppException.class,()->termination.propose(rentalId,"tenant",new RentalWorkflowPayloads.Termination(0L,input.endExclusive(),input.reason(),input.handoverTerms(),input.finalRentPolicy(),List.of(new RentalWorkflowPayloads.RentRefund(deposit,BigDecimal.ONE)),List.of())));}
    @Test void parallelHandoverConfirmationCannotApplySameReviewedRevisionTwice() throws Exception {
        var p=handover.propose(rentalId,"owner",move(0));var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);var start=new CountDownLatch(2);
        try{var calls=new ArrayList<Future<Boolean>>();for(String actor:List.of("owner","tenant"))calls.add(pool.submit(()->{start.countDown();go.await();try{handover.act(rentalId,actor,"CONFIRM",action(p.revision()));return true;}catch(AppException e){return false;}}));assertTrue(start.await(5,TimeUnit.SECONDS));go.countDown();int wins=0;for(var f:calls)if(f.get(15,TimeUnit.SECONDS))wins++;assertEquals(1,wins);assertEquals(2,handover.get(rentalId,"owner").revision());}finally{pool.shutdownNow();}
    }
    @Test void moveInEvidenceIsPrivateBoundToHistoryAndCannotBeDeletedAfterCorrection(){
        String[] file={null};tx(()->file[0]=storage.saveAndFlush(StorageObject.builder().id(UUID.randomUUID().toString()).ownerId("owner").originalName("handover.png").contentType("image/png").sizeBytes(100L).bucketName("test").objectKey("test/"+UUID.randomUUID()).purpose(StoragePurpose.GENERAL).visibility(StorageVisibility.PRIVATE).status(StorageStatus.READY).referenceType("RENTAL_COMPLAINT_EVIDENCE").referenceId(rentalId).build()).getId());
        var f=storage.findById(file[0]).orElseThrow();assertFalse(workflowAccess.canAccess("tenant",f));
        var p=move(0);var withEvidence=new RentalWorkflowPayloads.Handover(0L,p.handedOverAt(),p.keys(),p.condition(),p.reason(),p.electricity(),p.water(),p.inventory(),List.of(file[0]));var v=handover.propose(rentalId,"owner",withEvidence);
        assertTrue(workflowAccess.canAccess("tenant",f));assertFalse(workflowAccess.canAccess("stranger",f));assertTrue(workflowAccess.preventsDeletion(f));
        handover.propose(rentalId,"owner",move(v.revision()));assertTrue(workflowAccess.canAccess("tenant",f));assertTrue(workflowAccess.preventsDeletion(f));
        tx(()->{var w=workflows.findById(rentalId+":MOVE_IN").orElseThrow();w.setHistoryJson("broken audit");workflows.save(w);});assertFalse(workflowAccess.canAccess("tenant",f));assertTrue(workflowAccess.preventsDeletion(f));
    }
    @Test void evidenceFromOtherRentalOrOtherUploaderCannotEnterHandover(){
        String[] file={null};tx(()->file[0]=storage.saveAndFlush(StorageObject.builder().id(UUID.randomUUID().toString()).ownerId("tenant").originalName("handover.png").contentType("image/png").sizeBytes(100L).bucketName("test").objectKey("test/"+UUID.randomUUID()).purpose(StoragePurpose.GENERAL).visibility(StorageVisibility.PRIVATE).status(StorageStatus.READY).referenceType("RENTAL_COMPLAINT_EVIDENCE").referenceId(rentalId).build()).getId());
        var p=move(0);assertThrows(AppException.class,()->handover.propose(rentalId,"owner",new RentalWorkflowPayloads.Handover(0L,p.handedOverAt(),p.keys(),p.condition(),p.reason(),p.electricity(),p.water(),p.inventory(),List.of(file[0]))));
    }
    @Test void confirmedRefundClosesPreTenancyMoneyObligationWithoutAnotherRelease(){var p=proposeCancel();var done=cancellation.act(rentalId,"tenant","ACCEPT",action(p.revision()));String refund=done.refundPayments().getFirst().id();tx(()->{var cash=paymentRepo.findById(refund).orElseThrow();cash.setStatus(PaymentStatus.TRANSFER_REPORTED);paymentRepo.save(cash);});payments.confirmReceipt(refund,"tenant");assertEquals("REFUNDED",cancellation.get(rentalId,"owner").depositObligation());verify(parking,times(1)).releaseReservationsForRequest(rentalId);}
    @Test void anotherReservationIsNeverReleasedByOldCancellation(){var p=proposeCancel();tx(()->rentals.saveAndFlush(RentalRequest.builder().id(UUID.randomUUID().toString()).listing(properties.findById(listingId).orElseThrow()).ownerId("owner").renterId("other").renterName("Other").renterPhone("0900000000").status(RentalRequestStatus.ACCEPTED).moveInDate(LocalDate.of(2026,12,14)).leaseMonths(1).monthlyRentPrice(BigDecimal.TEN).build()));assertThrows(AppException.class,()->cancellation.act(rentalId,"tenant","ACCEPT",action(p.revision())));verify(listings,never()).releaseReserved(any(),anyString(),anyString());}
    @Test void auditPersistenceFailureRollsBackSigningPauseAndNewRecord(){beforeLease();doThrow(new IllegalStateException("Audit write failed")).when(workflows).saveAndFlush(any());assertThrows(IllegalStateException.class,()->cancellation.propose(rentalId,"owner",new RentalWorkflowPayloads.Cancellation(0L,"Hoàn lại tiền trước khi tiếp tục ký",List.of())));assertNull(rentals.findById(rentalId).orElseThrow().getResolutionWorkflowId());assertTrue(workflows.findById(rentalId+":CANCELLATION").isEmpty());}
    @Test void earlyExitStopsBillingAtAgreedDateAndCannotRenewOrSettleUnreturnedRoom(){var p=termination.propose(rentalId,"tenant",early(0));termination.act(rentalId,"owner","ACCEPT",action(p.revision()));clock("2026-12-25T10:00:00");billing.syncContract(id);var last=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();assertEquals(LocalDate.of(2026,12,20),last.getPeriodEndExclusive());assertEquals(3,invoices.findByContractIdOrderByPeriodIndexDesc(id).size());assertFalse(service.get(id,"owner").canProposeClosure());assertThrows(AppException.class,()->service.requestRenewal(id,"tenant",new RenewalPayload(1,"Muốn tiếp tục gia hạn thêm một tháng")));}
    @Test void activeTerminationComplaintBlocksFinancialSettlementAfterBothReturned(){var p=termination.propose(rentalId,"tenant",early(0));var agreed=termination.act(rentalId,"owner","ACCEPT",action(p.revision()));clock("2026-12-20T10:00:00");var a=termination.act(rentalId,"tenant","CONFIRM_RETURN",action(agreed.revision()));var b=termination.act(rentalId,"owner","CONFIRM_RETURN",action(a.revision()));termination.act(rentalId,"tenant","REPORT_ISSUE",action(b.revision()));tx(()->{var last=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();last.setStatus(MonthlyInvoiceStatus.PAID);invoices.save(last);});assertFalse(service.get(id,"owner").canProposeClosure());assertThrows(AppException.class,()->service.proposeClosure(id,"owner",new ClosurePayload(null,null,BigDecimal.ZERO,"Đã bàn giao và cần quyết toán cọc",true)));}
    @Test void parallelApprovalAndReceiptRetriesCreateOneRefundAndReleaseOnlyOnce() throws Exception {
        var proposal=proposeCancel();
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(2);
        var go=new CountDownLatch(1);
        try {
            Callable<Boolean> approve=()->{
                start.countDown();go.await();
                try {cancellation.act(rentalId,"tenant","ACCEPT",action(proposal.revision()));return true;}
                catch(AppException e){return false;}
            };
            var first=pool.submit(approve);
            var second=pool.submit(approve);
            assertTrue(start.await(5,TimeUnit.SECONDS));go.countDown();
            assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            var refunds=paymentRepo.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.INITIAL_PAYMENT_REFUND));
            assertEquals(1,refunds.size());assertAwaitingRefund();
            String refund=refunds.getFirst().getId();markRefundReported(refund);
            var receipt=pool.submit(()->payments.confirmReceipt(refund,"tenant"));
            var retry=pool.submit(()->payments.confirmReceipt(refund,"tenant"));
            assertEquals(PaymentStatus.CONFIRMED,receipt.get(15,TimeUnit.SECONDS).status());
            assertEquals(PaymentStatus.CONFIRMED,retry.get(15,TimeUnit.SECONDS).status());
            verify(parking,times(1)).releaseReservationsForRequest(rentalId);
            assertEquals(List.of("RENTAL_CANCELLATION_REQUESTED:RENTER:tenant","RENTAL_CANCELLATION_REFUND_PENDING:OWNER:owner","RENTAL_REQUEST_CANCELLED:OWNER:owner"),events());
        } finally {pool.shutdownNow();}
    }
    String evidence(String actor){String id=UUID.randomUUID().toString();tx(()->storage.saveAndFlush(StorageObject.builder().id(id).ownerId(actor).originalName("source.pdf").objectKey("source/"+id).bucketName("test").contentType("application/pdf").sizeBytes(100L).purpose(StoragePurpose.GENERAL).visibility(StorageVisibility.PRIVATE).status(StorageStatus.READY).referenceType("RENTAL_COMPLAINT_EVIDENCE").referenceId(rentalId).build()));return id;}
    ComplaintAction complaintAction(ComplaintView c){return new ComplaintAction(c.revision(),"Đã kiểm tra căn cứ và phản hồi hồ sơ",null,null,null,null);}
    void admin(){UserContextHolder.set(new UserContext("admin",null));SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin","n/a",List.of(new SimpleGrantedAuthority("ADMIN"))));}
    @AfterEach void clearActors(){UserContextHolder.clear();SecurityContextHolder.clearContext();}
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"MOVE_IN","CANCELLATION","EARLY_EXIT"})
    void sourceEvidenceFromOtherPartyReachesAdminAndDoesNotDrift(String kind){
        String file=evidence("owner"),unused=evidence("tenant");RentalWorkflowView w;
        if("MOVE_IN".equals(kind)){var p=move(0);w=handover.propose(rentalId,"owner",new RentalWorkflowPayloads.Handover(0L,p.handedOverAt(),p.keys(),p.condition(),p.reason(),p.electricity(),p.water(),p.inventory(),List.of(file)));w=handover.act(rentalId,"tenant","REPORT_ISSUE",action(w.revision()));}
        else if("CANCELLATION".equals(kind)){beforeLease();w=cancellation.propose(rentalId,"owner",new RentalWorkflowPayloads.Cancellation(0L,"Đề nghị đối chiếu hồ sơ trước thuê",List.of(file)));w=cancellation.act(rentalId,"tenant","ESCALATE",action(w.revision()));}
        else{var p=early(0);w=termination.propose(rentalId,"owner",new RentalWorkflowPayloads.Termination(0L,p.endExclusive(),p.reason(),p.handoverTerms(),p.finalRentPolicy(),p.rentRefunds(),List.of(file)));w=termination.act(rentalId,"tenant","REPORT_ISSUE",action(w.revision()));}
        var c=complaints.get(w.complaintId(),"tenant");assertEquals(1,c.sources().size());assertEquals(kind,c.sources().getFirst().kind());assertTrue(c.evidenceIds().contains(file));assertFalse(c.evidenceIds().contains(unused));assertTrue(access.canAccess("tenant",storage.findById(file).orElseThrow()));assertFalse(access.canAccess("owner",storage.findById(unused).orElseThrow()));
        complaints.act(c.id(),"owner","COMMENT",complaintAction(c));c=complaints.get(c.id(),"tenant");c=complaints.act(c.id(),"tenant","ESCALATE",complaintAction(c));admin();var read=complaints.adminGet(c.id(),"admin");assertTrue(read.sources().getFirst().evidenceIds().contains(file));long captured=read.sources().getFirst().revision();
        tx(()->{var row=workflows.findById(rentalId+":"+kind).orElseThrow();row.setPayloadJson("{\"reason\":\"Nội dung đã đổi sau khi chụp\"}");row.setRevision(row.getRevision()+1);workflows.save(row);});
        assertEquals(captured,complaints.adminGet(c.id(),"admin").sources().getFirst().revision());assertFalse(complaints.adminGet(c.id(),"admin").sources().getFirst().proposal().get("reason").equals("Nội dung đã đổi sau khi chụp"));
    }
    RentalWorkflowView resolvedCancellation(){var w=proposeCancel();w=cancellation.act(rentalId,"tenant","ESCALATE",action(w.revision()));var c=complaints.get(w.complaintId(),"owner");c=complaints.act(c.id(),"owner","PROPOSE",complaintAction(c));complaints.act(c.id(),"tenant","ACCEPT",complaintAction(c));return cancellation.get(rentalId,"owner");}
    @Test void resolvedComplaintNeedsTwoExplicitResumeConsentsWithoutNewCancellation(){var w=resolvedCancellation();assertTrue(w.allowedActions().contains("REQUEST_RESUME"));assertNotNull(rentals.findById(rentalId).orElseThrow().getResolutionWorkflowId());var requested=cancellation.act(rentalId,"owner","REQUEST_RESUME",action(w.revision()));assertEquals("RESUME_PROPOSED",requested.status());assertNotNull(rentals.findById(rentalId).orElseThrow().getResolutionWorkflowId());assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","RESUME",action(requested.revision())));var resumed=cancellation.act(rentalId,"tenant","RESUME",action(requested.revision()));assertEquals("RESUMED",resumed.status());assertNull(rentals.findById(rentalId).orElseThrow().getResolutionWorkflowId());assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());assertTrue(paymentRepo.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.INITIAL_PAYMENT_REFUND)).isEmpty());}
    @Test void resumeRejectsOpenComplaintStaleVersionAndUnknownSignature(){var w=proposeCancel();w=cancellation.act(rentalId,"tenant","ESCALATE",action(w.revision()));var open=w;assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","REQUEST_RESUME",action(open.revision())));var c=complaints.get(w.complaintId(),"owner");c=complaints.act(c.id(),"owner","PROPOSE",complaintAction(c));complaints.act(c.id(),"tenant","ACCEPT",complaintAction(c));w=cancellation.get(rentalId,"owner");var resolved=w;assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","REQUEST_RESUME",action(0)));tx(()->signatures.saveAndFlush(SignatureRequest.builder().contractId(id).revisionId(contracts.findById(id).orElseThrow().getCurrentRevisionId()).sourceDocumentId("source").docId(UUID.randomUUID().toString()).signerRole(SignerRole.TENANT).signerUserId("tenant").status(SignatureRequestStatus.PROVIDER_SIGNED).build()));assertThrows(AppException.class,()->cancellation.act(rentalId,"owner","REQUEST_RESUME",action(resolved.revision())));}
    @Test void earlyViewReflectsActualFinancialCompletionAndPendingRefund(){var p=termination.propose(rentalId,"tenant",early(0));var agreed=termination.act(rentalId,"owner","ACCEPT",action(p.revision()));clock("2026-12-20T10:00:00");var returned=termination.act(rentalId,"tenant","CONFIRM_RETURN",action(agreed.revision()));returned=termination.act(rentalId,"owner","CONFIRM_RETURN",action(returned.revision()));assertEquals("BILLS_PENDING",returned.depositObligation());tx(()->{var b=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();b.setStatus(MonthlyInvoiceStatus.PAID);invoices.save(b);});var proposal=service.proposeClosure(id,"owner",new ClosurePayload(null,null,BigDecimal.ZERO,"Đã trả nhà và hoàn toàn bộ khoản tiền cọc",true));var settlement=service.acceptClosure(id,"tenant",new AcceptClosurePayload(proposal.closure().getProposalVersion(),true,true));assertEquals("REFUND_PENDING",termination.get(rentalId,"tenant").depositObligation());tx(()->{var cash=paymentRepo.findById(settlement.refundPayment().id()).orElseThrow();cash.setStatus(PaymentStatus.TRANSFER_REPORTED);paymentRepo.save(cash);});payments.confirmReceipt(settlement.refundPayment().id(),"tenant");assertEquals("COMPLETED",termination.get(rentalId,"tenant").depositObligation());}
    String prepaidRent(){String[] result={null};tx(()->{var cash=paymentRepo.saveAndFlush(PaymentRequest.builder().contractId(id).rentalRequestId(rentalId).listingId(listingId).payerId("tenant").payeeId("owner").type(PaymentType.MONTHLY_RENT).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("5000000")).confirmedAt(time.now()).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(cash.getId()).type("RENT").displayName("Tiền thuê kỳ cuối đã nhận").amount(new BigDecimal("5000000")).unitPrice(new BigDecimal("5000000")).quantity(1).build());var bill=invoices.findByContractIdAndPeriodIndex(id,1).orElseThrow();bill.setPaymentRequestId(cash.getId());bill.setRentPeriodStart(LocalDate.of(2026,12,12));bill.setRentPeriodEndExclusive(LocalDate.of(2027,1,12));invoices.save(bill);result[0]=cash.getId();});return result[0];}
    @Test void dailyProrationRequiresEnoughRealRefundAndDoesNotEditReceipt(){String source=prepaidRent();var quote=termination.calculate(rentalId,"tenant",LocalDate.of(2026,12,20),"DAILY_PRORATED");assertEquals(8,quote.usedDays());assertEquals(31,quote.periodDays());assertEquals(new BigDecimal("1290323"),quote.agreedFinalRent());assertEquals(0,new BigDecimal("3709677").compareTo(quote.requiredRefund()));var old=early(0);assertThrows(AppException.class,()->termination.propose(rentalId,"tenant",new RentalWorkflowPayloads.Termination(0L,old.endExclusive(),old.reason(),old.handoverTerms(),"DAILY_PRORATED",List.of(),List.of())));var p=termination.propose(rentalId,"tenant",new RentalWorkflowPayloads.Termination(0L,old.endExclusive(),old.reason(),old.handoverTerms(),"DAILY_PRORATED",List.of(new RentalWorkflowPayloads.RentRefund(source,quote.requiredRefund())),List.of()));assertEquals(quote.requiredRefund(),p.rentCalculation().requiredRefund());var done=termination.act(rentalId,"owner","ACCEPT",action(p.revision()));assertEquals(0,quote.requiredRefund().compareTo(done.refundPayments().getFirst().totalAmount()));assertEquals(new BigDecimal("5000000.00"),paymentRepo.findById(source).orElseThrow().getTotalAmount());assertEquals("RENT_REFUND_PENDING",done.depositObligation());}
    @Test void dailyProrationDoesNotRefundUnreceivedOrUnidentifiedRent(){assertThrows(AppException.class,()->termination.calculate(rentalId,"tenant",LocalDate.of(2026,12,20),"DAILY_PRORATED"));assertTrue(paymentRepo.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.INITIAL_PAYMENT_REFUND)).isEmpty());}
    @Test void documentIsSeparateVersionedAndOldPdfCannotSignNewProposal(){var w=handover.propose(rentalId,"owner",move(0));var doc=workflowDocuments.prepare(rentalId,"move-in","tenant",w.revision());assertTrue(doc.current());var envelope=contracts.findById(doc.signingContractId()).orElseThrow();assertEquals("WORKFLOW",envelope.getAgreementKind());assertEquals(id,envelope.getParentContractId());assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());assertEquals(doc.signingContractId(),workflowDocuments.prepare(rentalId,"move-in","owner",w.revision()).signingContractId());service.validateAgreementSigning(envelope);handover.propose(rentalId,"owner",move(w.revision()));assertThrows(AppException.class,()->service.validateAgreementSigning(envelope));assertFalse(workflowDocuments.list(rentalId,"move-in","tenant").getFirst().current());verify(parking,never()).activateRenewalHold(anyString(),anyString());}
    void verifiedDocument(Contract envelope){tx(()->{for(var role:SignerRole.values())signatures.saveAndFlush(SignatureRequest.builder().contractId(envelope.getId()).revisionId(envelope.getCurrentRevisionId()).sourceDocumentId("source").docId(UUID.randomUUID().toString()).signerRole(role).signerUserId(role==SignerRole.LANDLORD?"owner":"tenant").status(SignatureRequestStatus.SIGNED).build());documents.saveAndFlush(ContractDocument.builder().contractId(envelope.getId()).revisionId(envelope.getCurrentRevisionId()).templateVersionId("template-v1").documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.SIGNED_FINAL).status(DocumentGenerationStatus.READY).storageObjectId("signed-final").build());});}
    @Test void verifiedSeparateHandoverSignaturesConfirmOnlyHandoverAndPreserveLedger(){var w=handover.propose(rentalId,"owner",move(0));var doc=workflowDocuments.prepare(rentalId,"move-in","owner",w.revision());var c=contracts.findById(doc.signingContractId()).orElseThrow();assertThrows(AppException.class,()->workflowDocuments.bothSigned(c));verifiedDocument(c);workflowDocuments.bothSigned(c);assertEquals("COMPLETED",handover.get(rentalId,"tenant").status());assertNotNull(workflowDocumentRecords.findBySigningContractId(c.getId()).orElseThrow().getSignedAt());assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());workflowDocuments.bothSigned(c);verify(parking,never()).activateRenewalHold(anyString(),anyString());}
    @Test void verifiedTerminationDocumentAppliesAgreementOnceAndNeverMarksMoneyReceived(){var w=termination.propose(rentalId,"tenant",early(0));var doc=workflowDocuments.prepare(rentalId,"early-exit","owner",w.revision());var c=contracts.findById(doc.signingContractId()).orElseThrow();verifiedDocument(c);workflowDocuments.bothSigned(c);assertEquals("AGREED",termination.get(rentalId,"tenant").status());assertEquals(LocalDate.of(2026,12,20),contracts.findById(id).orElseThrow().getAgreedEarlyEndExclusive());assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());workflowDocuments.bothSigned(c);assertEquals(List.of("TERMINATION_RESPONSE_NEEDED:owner"),terminationEvents());assertEquals(2,handoverOutbox.findAll().stream().map(r->NOTICE_JSON.readTree(r.getPayload())).filter(e->"AGREEMENT_FULLY_SIGNED".equals(e.get("eventType").asText())&&c.getId().equals(e.get("data").get("agreementId").asText())).count());}
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"AGREED","RETURNED"})
    void verifiedTerminationDocumentNotifiesWhenAgreementWasAlreadyAccepted(String status){
        var proposed=termination.propose(rentalId,"tenant",early(0));
        var agreed=termination.act(rentalId,"owner","ACCEPT",action(proposed.revision()));
        if("RETURNED".equals(status)){
            clock("2026-12-20T10:00:00");
            var first=termination.act(rentalId,"tenant","CONFIRM_RETURN",action(agreed.revision()));
            termination.act(rentalId,"owner","CONFIRM_RETURN",action(first.revision()));
        }
        var current=termination.get(rentalId,"tenant");
        var doc=workflowDocuments.prepare(rentalId,"early-exit","owner",current.revision());
        var envelope=contracts.findById(doc.signingContractId()).orElseThrow();verifiedDocument(envelope);
        handoverOutbox.deleteAll();
        workflowDocuments.bothSigned(envelope);workflowDocuments.bothSigned(envelope);
        assertEquals(status,termination.get(rentalId,"tenant").status());assertTrue(terminationEvents().isEmpty());
        assertEquals(List.of("owner","tenant"),handoverOutbox.findAll().stream().sorted(Comparator.comparing(com.hs.contract.notification.ContractNotificationOutbox::getId)).map(row->{
            var event=NOTICE_JSON.readTree(row.getPayload());assertEquals(envelope.getId(),row.getAggregateId());
            assertEquals("AGREEMENT_FULLY_SIGNED",event.get("eventType").asText());
            assertEquals(envelope.getId(),event.get("data").get("agreementId").asText());
            assertEquals("EARLY_EXIT",event.get("data").get("agreementKind").asText());
            return event.get("recipients").get(0).asText();
        }).toList());
    }
    @Test void verifiedTerminationAgreementAndSigningNoticesRollbackTogether(){
        var proposed=termination.propose(rentalId,"tenant",early(0));
        var doc=workflowDocuments.prepare(rentalId,"early-exit","owner",proposed.revision());
        var envelope=contracts.findById(doc.signingContractId()).orElseThrow();verifiedDocument(envelope);handoverOutbox.deleteAll();
        assertThrows(IllegalStateException.class,()->tx(()->{workflowDocuments.bothSigned(envelope);throw new IllegalStateException("rollback after signed notices");}));
        assertEquals("PROPOSED",termination.get(rentalId,"tenant").status());
        assertNull(contracts.findById(id).orElseThrow().getAgreedEarlyEndExclusive());
        assertNull(workflowDocumentRecords.findBySigningContractId(envelope.getId()).orElseThrow().getSignedAt());assertEquals(0,handoverOutbox.count());
        workflowDocuments.bothSigned(envelope);workflowDocuments.bothSigned(envelope);
        assertEquals("AGREED",termination.get(rentalId,"tenant").status());assertEquals(2,handoverOutbox.count());assertTrue(terminationEvents().isEmpty());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void lifecyclePaymentCancellationIsAtomicAndBillingReplayIsSilent(boolean unilateral){
        String[] billId={null};
        tx(()->{
            var bill=invoices.findByContractIdAndPeriodIndex(id,0).orElseThrow();billId[0]=bill.getId();
            var payment=paymentRepo.saveAndFlush(PaymentRequest.builder().rentalRequestId(rentalId).listingId(listingId).contractId(id).invoiceId(bill.getId())
                    .payerId("tenant").payeeId("owner").type(PaymentType.MONTHLY_RENT).direction(PaymentDirection.TENANT_TO_LANDLORD)
                    .status(PaymentStatus.CONFIRMED).totalAmount(BigDecimal.TEN).confirmedAt(time.now()).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());
            bill.setStatus(MonthlyInvoiceStatus.OVERDUE);bill.setPaymentRequestId(payment.getId());invoices.save(bill);
            var root=contracts.findById(id).orElseThrow();root.setTerminationProposalInvoiceId(bill.getId());root.setTerminationProposedAt(time.now());
            if(unilateral)root.setUnilateralTerminationSnapshot(support.json(List.of(UnilateralTerminationNotice.builder().id("notice").invoiceId(bill.getId()).version(1)
                    .status(UnilateralTerminationNotice.Status.NOTICE).notifiedAt(time.now()).respondBy(time.now().plusSeconds(86400)).build())));
            contracts.save(root);
        });
        assertThrows(IllegalStateException.class,()->tx(()->{service.get(id,"tenant");throw new IllegalStateException("rollback after cancellation notice");}));
        assertNull(contracts.findById(id).orElseThrow().getTerminationCancelledAt());
        assertEquals(MonthlyInvoiceStatus.OVERDUE,invoices.findById(billId[0]).orElseThrow().getStatus());assertTrue(terminationEvents().isEmpty());
        service.get(id,"tenant");service.get(id,"tenant");billing.list(id,"tenant");service.get(id,"tenant");
        assertNotNull(contracts.findById(id).orElseThrow().getTerminationCancelledAt());
        assertEquals(List.of((unilateral?"TERMINATION_NOTICE_CANCELLED":"TERMINATION_WITHDRAWN")+":tenant"),terminationEvents());
        if(unilateral)assertEquals("CANCELLED_PAID",NOTICE_JSON.readTree(contracts.findById(id).orElseThrow().getUnilateralTerminationSnapshot()).get(0).get("status").asText());
    }
    @Test void preparedPdfIsCapturedIntoComplaintAndBoundToTheSameSource(){
        var w=handover.propose(rentalId,"owner",move(0));var doc=workflowDocuments.prepare(rentalId,"move-in","owner",w.revision());
        var dispute=handover.act(rentalId,"tenant","REPORT_ISSUE",action(w.revision()));var c=complaints.get(dispute.complaintId(),"tenant");
        var captured=c.sources().getFirst();assertEquals(1,captured.documents().size());assertEquals(doc.signingContractId(),captured.documents().getFirst().signingContractId());
        assertTrue(c.evidenceIds().contains(captured.documents().getFirst().storageObjectId()));assertNotNull(captured.contentHash());
        assertTrue(documentRetention.preventsDeletion(storage.findById(captured.documents().getFirst().storageObjectId()).orElseThrow()));
        complaints.act(c.id(),"owner","COMMENT",complaintAction(c));c=complaints.get(c.id(),"tenant");c=complaints.act(c.id(),"tenant","ESCALATE",complaintAction(c));admin();assertEquals(captured.documents(),complaints.adminGet(c.id(),"admin").sources().getFirst().documents());
    }
    @Test void wrongPartyOrChangedRootRevisionCannotSendOrApplyOldPdf(){
        var w=handover.propose(rentalId,"owner",move(0));var doc=workflowDocuments.prepare(rentalId,"move-in","owner",w.revision());var envelope=contracts.findById(doc.signingContractId()).orElseThrow();
        envelope.setTenantId("stranger");assertThrows(AppException.class,()->service.validateAgreementSigning(envelope));envelope.setTenantId("tenant");
        tx(()->{var c=contracts.findById(id).orElseThrow();var rev=new ContractRevision();org.springframework.beans.BeanUtils.copyProperties(revisions.findById(c.getCurrentRevisionId()).orElseThrow(),rev,"id","createdAt","updatedAt");rev.setRevisionNumber(2);rev=revisions.saveAndFlush(rev);c.setCurrentRevisionId(rev.getId());contracts.save(c);});
        assertThrows(AppException.class,()->workflowDocuments.prepare(rentalId,"move-in","owner",w.revision()));assertThrows(AppException.class,()->workflowDocuments.bothSigned(envelope));
        assertEquals("PROPOSED",handover.get(rentalId,"owner").status());
    }
    @Test void renderingFailureDoesNotCreateAnEnvelopeOrChangeSource(){
        var w=handover.propose(rentalId,"owner",move(0));when(workflowRenderer.render(any(),any(),any())).thenThrow(new IllegalStateException("Converter unavailable"));
        assertThrows(IllegalStateException.class,()->workflowDocuments.prepare(rentalId,"move-in","tenant",w.revision()));
        assertTrue(workflowDocumentRecords.findByWorkflowIdOrderByPreparedAtDesc(rentalId+":MOVE_IN").isEmpty());assertEquals(w.revision(),handover.get(rentalId,"tenant").revision());
    }

    // ---- Phase 5: move-in handover notices (outbox rows are asserted on the real PostgreSQL schema) ----
    @MockitoSpyBean com.hs.contract.notification.MoveInHandoverNotificationWriter handoverWriter;
    @Autowired com.hs.contract.notification.ContractNotificationOutboxRepository handoverOutbox;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final java.util.concurrent.atomic.AtomicBoolean failAfterHandoverNotice=new java.util.concurrent.atomic.AtomicBoolean();
    static final tools.jackson.databind.json.JsonMapper NOTICE_JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    @BeforeEach void armHandoverWriter(){
        assertEquals(selectedDatabaseName(),jdbc.queryForObject("select current_database()",String.class),"connected database must be the one named by the allowlisted URL");
        failAfterHandoverNotice.set(false);
        var target=org.springframework.test.util.AopTestUtils.<com.hs.contract.notification.MoveInHandoverNotificationWriter>getUltimateTargetObject(handoverWriter);
        doAnswer(call->{call.callRealMethod();if(failAfterHandoverNotice.get())throw new IllegalStateException("test after enqueue");return null;}).when(target).enqueueOther(any(),any(),any());
        doAnswer(call->{call.callRealMethod();if(failAfterHandoverNotice.get())throw new IllegalStateException("test after enqueue");return null;}).when(target).enqueueBoth(any(),any());
    }
    /** This lease's handover notices as TYPE:recipient in enqueue (FIFO) order. */
    List<String> noticeEvents(){
        return handoverOutbox.findAll().stream().sorted(Comparator.comparing(com.hs.contract.notification.ContractNotificationOutbox::getId)).map(r->{
            var e=NOTICE_JSON.readTree(r.getPayload());
            if(!e.get("eventType").asText().startsWith("MOVE_IN_HANDOVER_")||!id.equals(e.get("data").get("contractId").asText()))return null;
            assertEquals(id,r.getAggregateId());assertNull(r.getSentAt());assertEquals(1,e.get("recipients").size());assertEquals(1,e.get("data").size());
            return e.get("eventType").asText().substring("MOVE_IN_HANDOVER_".length())+":"+e.get("recipients").get(0).asText();
        }).filter(Objects::nonNull).toList();
    }
    List<String> terminationEvents(){
        return handoverOutbox.findAll().stream().sorted(Comparator.comparing(com.hs.contract.notification.ContractNotificationOutbox::getId)).map(r->{
            var e=NOTICE_JSON.readTree(r.getPayload());
            if(!id.equals(r.getAggregateId())||!e.get("eventType").asText().startsWith("TERMINATION_")&&!"LEASE_EXPIRED".equals(e.get("eventType").asText()))return null;
            assertEquals(id,e.get("data").get("contractId").asText());assertEquals(1,e.get("data").size());assertEquals(1,e.get("recipients").size());
            return e.get("eventType").asText()+":"+e.get("recipients").get(0).asText();
        }).filter(Objects::nonNull).toList();
    }
    RentalWorkflowView confirm(String who,RentalWorkflowView w){return handover.act(rentalId,who,"CONFIRM",action(w.revision()));}
    RentalWorkflowView dispute(String who,RentalWorkflowView w){return handover.act(rentalId,who,"REPORT_ISSUE",action(w.revision()));}
    RentalWorkflowView reproposal(RentalWorkflowView w){return handover.propose(rentalId,"owner",move(w.revision()));}
    Contract signedEnvelope(RentalWorkflowView w){var doc=workflowDocuments.prepare(rentalId,"move-in","owner",w.revision());var c=contracts.findById(doc.signingContractId()).orElseThrow();verifiedDocument(c);return c;}

    @Test void everyCommittedProposalNotifiesOnlyTheTenantIncludingReplacementOfProposedCompletedAndDisputed(){
        var p=handover.propose(rentalId,"owner",move(0));
        var tenantConfirmed=confirm("tenant",p);
        var replaced=reproposal(tenantConfirmed);assertNull(replaced.tenantConfirmedAt());assertNull(replaced.ownerConfirmedAt());
        var done=confirm("tenant",confirm("owner",replaced));assertEquals("COMPLETED",done.status());
        var reopened=reproposal(done);assertEquals("PROPOSED",reopened.status());
        var disputed=dispute("tenant",reopened);assertEquals("DISPUTED",disputed.status());
        var afterDispute=reproposal(disputed);assertEquals("PROPOSED",afterDispute.status());
        assertEquals(List.of("PROPOSED:tenant","CONFIRMED:owner","PROPOSED:tenant","CONFIRMED:tenant","COMPLETED:owner","PROPOSED:tenant","DISPUTED:owner","PROPOSED:tenant"),noticeEvents());
    }
    @Test void tenantFirstConfirmationNotifiesLandlordAndLandlordCompletionNotifiesTenantOnly(){
        var p=handover.propose(rentalId,"owner",move(0));var one=confirm("tenant",p);var done=confirm("owner",one);
        assertEquals("COMPLETED",done.status());
        assertEquals(List.of("PROPOSED:tenant","CONFIRMED:owner","COMPLETED:tenant"),noticeEvents());
    }
    @Test void landlordFirstConfirmationNotifiesTenantAndTenantCompletionNotifiesLandlordOnly(){
        var p=handover.propose(rentalId,"owner",move(0));var one=confirm("owner",p);var done=confirm("tenant",one);
        assertEquals("COMPLETED",done.status());
        assertEquals(List.of("PROPOSED:tenant","CONFIRMED:tenant","COMPLETED:owner"),noticeEvents());
    }
    @Test void deniedStaleDuplicateInvalidReadOnlyAndPdfPrepareStaySilentAndNeverChangeTheRecord(){
        var p=handover.propose(rentalId,"owner",move(0));
        assertThrows(AppException.class,()->handover.propose(rentalId,"tenant",move(p.revision())));
        assertThrows(AppException.class,()->handover.act(rentalId,"stranger","CONFIRM",action(p.revision())));
        assertThrows(AppException.class,()->handover.act(rentalId,"stranger","REPORT_ISSUE",action(p.revision())));
        assertThrows(AppException.class,()->handover.act(rentalId,"tenant","CONFIRM",action(p.revision()+5)));
        assertThrows(AppException.class,()->handover.act(rentalId,"tenant","CONFIRM",new RentalWorkflowPayloads.Action(p.revision(),"Đã đối chiếu nội dung và xác nhận thực tế",false,true)));
        assertThrows(AppException.class,()->handover.act(rentalId,"tenant","WITHDRAW",action(p.revision())));
        assertThrows(AppException.class,()->handover.propose(rentalId,"owner",new RentalWorkflowPayloads.Handover(p.revision(),time.now().plusSeconds(60),2,"Phòng đúng hiện trạng đã đối chiếu","Biên bản nhận nhà thực tế đầu kỳ",BigDecimal.TEN,BigDecimal.ONE,List.of(),List.of())));
        handover.get(rentalId,"tenant");handover.get(rentalId,"owner");workflowDocuments.prepare(rentalId,"move-in","tenant",p.revision());workflowDocuments.list(rentalId,"move-in","owner");
        var one=confirm("tenant",p);
        assertThrows(AppException.class,()->confirm("tenant",one));            // duplicate confirmation by the same party
        assertThrows(AppException.class,()->handover.act(rentalId,"owner","CONFIRM",action(p.revision()))); // stale revision
        assertEquals(List.of("PROPOSED:tenant","CONFIRMED:owner"),noticeEvents());
        tx(()->{var c=contracts.findById(id).orElseThrow();c.setStatus(ContractStatus.CLOSED);contracts.save(c);});
        assertThrows(AppException.class,()->confirm("owner",one));assertThrows(AppException.class,()->dispute("owner",one));assertThrows(AppException.class,()->reproposal(one));
        assertEquals(List.of("PROPOSED:tenant","CONFIRMED:owner"),noticeEvents());
        assertEquals(one.revision(),handover.get(rentalId,"owner").revision());
    }
    @Test void revisionZeroDisputeNotifiesTheOtherPartyOnceDespiteTwoSavesAndARepeatedDisputeIsSilentButStillRecorded(){
        var first=dispute("tenant",handover.get(rentalId,"tenant"));
        assertEquals("DISPUTED",first.status());assertEquals(2,first.revision());
        assertEquals(List.of("DISPUTED:owner"),noticeEvents());
        var repeat=dispute("tenant",first);
        assertEquals("DISPUTED",repeat.status());assertEquals(first.revision()+1,repeat.revision());assertEquals(first.complaintId(),repeat.complaintId());
        var byLandlord=dispute("owner",repeat);assertEquals(repeat.revision()+1,byLandlord.revision());
        assertEquals(List.of("DISPUTED:owner"),noticeEvents());
        var proposed=reproposal(byLandlord);
        var again=dispute("owner",proposed);assertEquals("DISPUTED",again.status());
        assertEquals(List.of("DISPUTED:owner","PROPOSED:tenant","DISPUTED:tenant"),noticeEvents());
    }
    @Test void disputeOfACompletedHandoverNotifiesTheOtherParty(){
        var done=confirm("tenant",confirm("owner",handover.propose(rentalId,"owner",move(0))));
        var disputed=dispute("owner",done);assertEquals("DISPUTED",disputed.status());
        assertEquals(List.of("PROPOSED:tenant","CONFIRMED:tenant","COMPLETED:owner","DISPUTED:tenant"),noticeEvents());
    }
    @Test void failureAfterTheNoticeRollsBackTheRecordTheComplaintAndTheOutboxTogether(){
        var p=handover.propose(rentalId,"owner",move(0));var one=confirm("tenant",p);
        long outboxRows=handoverOutbox.count(),caseRows=cases.count();
        failAfterHandoverNotice.set(true);
        assertThrows(IllegalStateException.class,()->handover.propose(rentalId,"owner",move(one.revision())));
        assertThrows(IllegalStateException.class,()->confirm("owner",one));           // completing confirmation
        assertThrows(IllegalStateException.class,()->dispute("tenant",one));
        failAfterHandoverNotice.set(false);
        var current=handover.get(rentalId,"owner");
        assertEquals(one.revision(),current.revision());assertEquals("PROPOSED",current.status());assertNotNull(current.tenantConfirmedAt());assertNull(current.ownerConfirmedAt());assertNull(current.complaintId());
        assertEquals(outboxRows,handoverOutbox.count());assertEquals(caseRows,cases.count());
        assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());
        assertEquals("COMPLETED",confirm("owner",one).status());   // the same call succeeds once and notifies once
        assertEquals(List.of("PROPOSED:tenant","CONFIRMED:owner","COMPLETED:tenant"),noticeEvents());
    }
    @Test void verifiedSignaturesWithNobodyConfirmedCompleteOnceForBothPartiesWithoutAnyIntermediateConfirmation(){
        var p=handover.propose(rentalId,"owner",move(0));var envelope=signedEnvelope(p);
        workflowDocuments.bothSigned(envelope);
        assertEquals("COMPLETED",handover.get(rentalId,"tenant").status());
        assertEquals(List.of("PROPOSED:tenant","COMPLETED:owner","COMPLETED:tenant"),noticeEvents());
        workflowDocuments.bothSigned(envelope);workflowDocuments.bothSigned(envelope);   // embedding replay
        assertEquals(3,noticeEvents().size());
        assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
    }
    @Test void verifiedSignaturesWithOnePartyAlreadyConfirmedCompleteForBothPartiesAndKeepOnlyTheHumanConfirmation(){
        var p=handover.propose(rentalId,"owner",move(0));var envelope=signedEnvelope(p);var one=confirm("owner",p);
        workflowDocuments.bothSigned(envelope);
        assertEquals("COMPLETED",handover.get(rentalId,"owner").status());assertEquals(one.revision()+1,handover.get(rentalId,"owner").revision());
        assertEquals(List.of("PROPOSED:tenant","CONFIRMED:tenant","COMPLETED:owner","COMPLETED:tenant"),noticeEvents());
    }
    @Test void verifiedSignaturesAfterBothPartiesAlreadyConfirmedCreateNoNewNotice(){
        var p=handover.propose(rentalId,"owner",move(0));var envelope=signedEnvelope(p);var done=confirm("tenant",confirm("owner",p));
        assertEquals("COMPLETED",done.status());
        workflowDocuments.bothSigned(envelope);
        assertEquals(done.revision(),handover.get(rentalId,"owner").revision());
        assertEquals(List.of("PROPOSED:tenant","CONFIRMED:tenant","COMPLETED:owner"),noticeEvents());
    }
    @Test void failureInTheSignatureTransactionRollsBackHandoverSigningRecordAndNoticesAndTheRetryNotifiesOnce(){
        var p=handover.propose(rentalId,"owner",move(0));var envelope=signedEnvelope(p);
        failAfterHandoverNotice.set(true);
        assertThrows(IllegalStateException.class,()->workflowDocuments.bothSigned(envelope));
        failAfterHandoverNotice.set(false);
        assertEquals("PROPOSED",handover.get(rentalId,"owner").status());assertEquals(p.revision(),handover.get(rentalId,"owner").revision());
        assertNull(workflowDocumentRecords.findBySigningContractId(envelope.getId()).orElseThrow().getSignedAt());
        assertEquals(List.of("PROPOSED:tenant"),noticeEvents());
        workflowDocuments.bothSigned(envelope);
        assertEquals(List.of("PROPOSED:tenant","COMPLETED:owner","COMPLETED:tenant"),noticeEvents());
    }
    @Test void concurrentConfirmationsKeepTheRevisionGuardAndNotifyExactlyOnce() throws Exception {
        var p=handover.propose(rentalId,"owner",move(0));var pool=Executors.newFixedThreadPool(3);var go=new CountDownLatch(1);var start=new CountDownLatch(3);
        try{
            var calls=new ArrayList<Future<String>>();
            for(String actor:List.of("owner","tenant","tenant"))calls.add(pool.submit(()->{start.countDown();go.await();try{handover.act(rentalId,actor,"CONFIRM",action(p.revision()));return actor;}catch(AppException e){return null;}}));
            assertTrue(start.await(5,TimeUnit.SECONDS));go.countDown();
            var winners=new ArrayList<String>();for(var f:calls){var w=f.get(20,TimeUnit.SECONDS);if(w!=null)winners.add(w);}
            assertEquals(1,winners.size());assertEquals(2,handover.get(rentalId,"owner").revision());assertEquals("PROPOSED",handover.get(rentalId,"owner").status());
            assertEquals(List.of("PROPOSED:tenant","CONFIRMED:"+("owner".equals(winners.getFirst())?"tenant":"owner")),noticeEvents());
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentCompletingAndDisputeAttemptsNotifyExactlyOncePerCommittedTransition() throws Exception {
        var p=handover.propose(rentalId,"owner",move(0));var one=confirm("tenant",p);var pool=Executors.newFixedThreadPool(2);
        try{
            var go=new CountDownLatch(1);var start=new CountDownLatch(2);var calls=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)calls.add(pool.submit(()->{start.countDown();go.await();try{handover.act(rentalId,"owner","CONFIRM",action(one.revision()));return true;}catch(AppException e){return false;}}));
            assertTrue(start.await(5,TimeUnit.SECONDS));go.countDown();int wins=0;for(var f:calls)if(f.get(20,TimeUnit.SECONDS))wins++;
            assertEquals(1,wins);assertEquals(List.of("PROPOSED:tenant","CONFIRMED:owner","COMPLETED:tenant"),noticeEvents());
            var done=handover.get(rentalId,"tenant");var go2=new CountDownLatch(1);var start2=new CountDownLatch(2);var disputes=new ArrayList<Future<Boolean>>();
            for(String actor:List.of("owner","tenant"))disputes.add(pool.submit(()->{start2.countDown();go2.await();try{handover.act(rentalId,actor,"REPORT_ISSUE",action(done.revision()));return true;}catch(AppException e){return false;}}));
            assertTrue(start2.await(5,TimeUnit.SECONDS));go2.countDown();wins=0;for(var f:disputes)if(f.get(20,TimeUnit.SECONDS))wins++;
            assertEquals(1,wins);assertEquals("DISPUTED",handover.get(rentalId,"owner").status());assertEquals(4,noticeEvents().size());assertTrue(noticeEvents().getLast().startsWith("DISPUTED:"));
        }finally{pool.shutdownNow();}
    }
    // Phase 6E uses the existing disposable-PostgreSQL workflow fixture and real transactional outboxes.
    @Autowired com.hs.payment.notification.PaymentNotificationOutboxRepository paymentNotices;
    @Autowired PaymentEventRepository paymentAudits;
    @MockitoSpyBean com.hs.payment.notification.PaymentNotificationWriter paymentNoticeWriter;

    List<String> settlementEvents(){
        var types=java.util.EnumSet.range(com.hs.contract.notification.TerminationNotificationWriter.Event.LEASE_RETURN_CONFIRMED,
                com.hs.contract.notification.TerminationNotificationWriter.Event.LEASE_CLOSED).stream().map(Enum::name).toList();
        return handoverOutbox.findAll().stream().sorted(Comparator.comparing(com.hs.contract.notification.ContractNotificationOutbox::getId))
                .filter(row->id.equals(row.getAggregateId())).map(row->{var e=NOTICE_JSON.readTree(row.getPayload());
                    return types.contains(e.get("eventType").asText())?e.get("eventType").asText()+":"+e.get("recipients").get(0).asText():null;
                }).filter(Objects::nonNull).toList();
    }
    List<String> cashEvents(String paymentId){
        return paymentNotices.findAll().stream().filter(row->paymentId.equals(row.getPaymentId()))
                .sorted(Comparator.comparing(com.hs.payment.notification.PaymentNotificationOutbox::getId)).map(row->{
                    var e=NOTICE_JSON.readTree(row.getPayload());assertEquals(1,e.get("data").size());assertEquals(paymentId,e.get("data").get("paymentId").asText());
                    assertEquals(1,e.get("recipients").size());return e.get("eventType").asText()+":"+e.get("recipients").get(0).asText();
                }).toList();
    }
    void readyClosure(){
        tx(()->{var last=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();last.setStatus(MonthlyInvoiceStatus.PAID);invoices.save(last);});
        clock("2027-01-14T10:00:00");service.get(id,"owner");
    }
    ClosurePayload closurePayload(String deduction,Integer version){return new ClosurePayload(null,null,new BigDecimal(deduction),
            "Đã đối chiếu hiện trạng và khoản khấu trừ thực tế",true,null,version);}
    ContractClosure closureProposal(String deduction){return service.proposeClosure(id,"owner",closurePayload(deduction,null)).closure();}
    void clearSettlementComplaint(){tx(()->cases.findByActiveKey(ContractLifecycleService.settlementComplaintKey(rentalId)).ifPresent(c->{c.setActiveKey(null);cases.save(c);}));}

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"owner","tenant"})
    void phase6ePhysicalReturnsNotifyTheOtherPartyInEitherOrderAndRejectReplay(String first){
        var proposal=termination.propose(rentalId,"tenant",early(0));var agreed=termination.act(rentalId,"owner","ACCEPT",action(proposal.revision()));
        assertThrows(AppException.class,()->termination.act(rentalId,first,"CONFIRM_RETURN",action(agreed.revision())));assertTrue(settlementEvents().isEmpty());
        clock("2026-12-20T10:00:00");String other=first.equals("owner")?"tenant":"owner";
        assertThrows(AppException.class,()->termination.act(rentalId,"outsider","CONFIRM_RETURN",action(agreed.revision())));
        var one=termination.act(rentalId,first,"CONFIRM_RETURN",action(agreed.revision()));
        assertEquals(List.of("LEASE_RETURN_CONFIRMED:"+other),settlementEvents());
        assertThrows(AppException.class,()->termination.act(rentalId,first,"CONFIRM_RETURN",action(one.revision())));
        assertThrows(AppException.class,()->termination.act(rentalId,other,"CONFIRM_RETURN",action(agreed.revision())));
        var done=termination.act(rentalId,other,"CONFIRM_RETURN",action(one.revision()));assertEquals("RETURNED",done.status());
        assertThrows(AppException.class,()->termination.act(rentalId,other,"CONFIRM_RETURN",action(done.revision())));
        assertEquals(List.of("LEASE_RETURN_CONFIRMED:"+other,"LEASE_RETURN_COMPLETED:"+first),settlementEvents());
    }
    @Test void phase6eReturnTimestampRevisionAndOutboxRollbackTogether(){
        var proposed=termination.propose(rentalId,"tenant",early(0));var agreed=termination.act(rentalId,"owner","ACCEPT",action(proposed.revision()));
        clock("2026-12-20T10:00:00");
        assertThrows(IllegalStateException.class,()->tx(()->{termination.act(rentalId,"owner","CONFIRM_RETURN",action(agreed.revision()));throw new IllegalStateException("after return enqueue");}));
        var stored=workflows.findById(rentalId+":EARLY_EXIT").orElseThrow();assertNull(stored.getOwnerConfirmedAt());assertEquals(agreed.revision(),stored.getRevision());assertTrue(settlementEvents().isEmpty());
        termination.act(rentalId,"owner","CONFIRM_RETURN",action(agreed.revision()));assertEquals(List.of("LEASE_RETURN_CONFIRMED:tenant"),settlementEvents());
    }
    @ParameterizedTest @CsvSource({"0,REFUND_PENDING,owner","21000000,BALANCE_PENDING,tenant","20000000,CLOSED,none"})
    void phase6eAcceptanceConsolidatesRefundBalanceAndZeroCashAndClosesOnlyAfterReceipt(String deduction,String status,String payer){
        readyClosure();var proposal=closureProposal(deduction);int version=proposal.getProposalVersion();
        assertThrows(AppException.class,()->service.acceptClosure(id,"outsider",new AcceptClosurePayload(version,true,true)));
        assertThrows(AppException.class,()->service.acceptClosure(id,"tenant",new AcceptClosurePayload(version+1,true,true)));
        var accepted=service.acceptClosure(id,"tenant",new AcceptClosurePayload(version,true,true));assertEquals(status,accepted.closure().getStatus());
        assertThrows(AppException.class,()->service.acceptClosure(id,"tenant",new AcceptClosurePayload(version,true,true)));
        if(payer.equals("none")){
            assertNull(accepted.refundPayment());assertNull(accepted.balancePayment());
            assertEquals(List.of("SETTLEMENT_RESPONSE_NEEDED:tenant","LEASE_CLOSED:owner","LEASE_CLOSED:tenant"),settlementEvents());
            assertTrue(paymentRepo.findAllByContractIdAndType(id,PaymentType.DEPOSIT_REFUND).isEmpty());
        }else{
            String paymentId=accepted.refundPayment()!=null?accepted.refundPayment().id():accepted.balancePayment().id();
            assertEquals(List.of("PAYMENT_REQUEST_CREATED:"+payer),cashEvents(paymentId));
            assertEquals(payer.equals("owner")?List.of("SETTLEMENT_RESPONSE_NEEDED:tenant"):
                    List.of("SETTLEMENT_RESPONSE_NEEDED:tenant","SETTLEMENT_AGREED:owner"),settlementEvents());
            tx(()->{var p=paymentRepo.findById(paymentId).orElseThrow();p.setStatus(PaymentStatus.TRANSFER_REPORTED);paymentRepo.save(p);});
            service.get(id,"tenant");assertNull(closures.findById(id).orElseThrow().getClosedAt());
            payments.confirmReceipt(paymentId,payer.equals("owner")?"tenant":"owner");
            assertNull(closures.findById(id).orElseThrow().getClosedAt());
            service.get(id,"owner");service.get(id,"tenant");assertEquals(ContractStatus.CLOSED,contracts.findById(id).orElseThrow().getStatus());
            assertEquals(List.of("LEASE_CLOSED:owner","LEASE_CLOSED:tenant"),settlementEvents().stream().filter(e->e.startsWith("LEASE_CLOSED")).toList());
            assertEquals(1,cashEvents(paymentId).stream().filter(e->e.startsWith("PAYMENT_RECEIPT_CONFIRMED")).count());
        }
        service.get(id,"owner");assertEquals(2,settlementEvents().stream().filter(e->e.startsWith("LEASE_CLOSED")).count());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"0","21000000","20000000"})
    void phase6eProposalAndAcceptanceRollbackIncludeDepositDomainAndBothOutboxes(String deduction){
        readyClosure();assertThrows(IllegalStateException.class,()->tx(()->{closureProposal(deduction);throw new IllegalStateException("after proposal enqueue");}));
        assertTrue(settlementEvents().isEmpty());assertEquals("WAITING_HANDOVER",closures.findById(id).orElseThrow().getStatus());
        var proposal=closureProposal(deduction);long paymentsBefore=paymentNotices.count(),auditsBefore=paymentAudits.count(),linesBefore=lines.count();
        assertThrows(IllegalStateException.class,()->tx(()->{service.acceptClosure(id,"tenant",new AcceptClosurePayload(proposal.getProposalVersion(),true,true));throw new IllegalStateException("after acceptance enqueue");}));
        var s=closures.findById(id).orElseThrow();assertEquals("PROPOSED",s.getStatus());assertNull(s.getAcceptedAt());assertNull(s.getClosedAt());
        assertEquals(0,principal.compareTo(deposits.findByRentalRequestId(rentalId).orElseThrow().getHeldAmount()));
        assertEquals(ContractStatus.EXPIRED,contracts.findById(id).orElseThrow().getStatus());assertEquals(paymentsBefore,paymentNotices.count());assertEquals(auditsBefore,paymentAudits.count());assertEquals(linesBefore,lines.count());
        assertEquals(List.of("SETTLEMENT_RESPONSE_NEEDED:tenant"),settlementEvents());
        service.acceptClosure(id,"tenant",new AcceptClosurePayload(s.getProposalVersion(),true,true));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void phase6eResponseDeadlineTargetsCurrentDecisionSideOnceAndResetsOnReproposal(boolean disputed){
        readyClosure();var proposal=closureProposal("0");
        if(disputed)service.disputeClosure(id,"tenant",new DisputeClosurePayload(proposal.getProposalVersion(),"Cần đối chiếu lại khoản khấu trừ",List.of()));
        var s=closures.findById(id).orElseThrow();time.instant=s.getRespondBy().minusNanos(1);service.get(id,"owner");assertNull(closures.findById(id).orElseThrow().getResponseOverdueAt());
        time.instant=s.getRespondBy();service.get(id,"tenant");service.get(id,"owner");
        assertEquals(List.of("SETTLEMENT_RESPONSE_OVERDUE:"+(disputed?"owner":"tenant")),settlementEvents().stream().filter(e->e.startsWith("SETTLEMENT_RESPONSE_OVERDUE")).toList());
        if(disputed){assertTrue(settlementEvents().contains("SETTLEMENT_DISPUTED:owner"));clearSettlementComplaint();}
        var replaced=service.proposeClosure(id,"owner",closurePayload("0",s.getProposalVersion())).closure();assertEquals(s.getProposalVersion()+1,replaced.getProposalVersion());assertNull(replaced.getResponseOverdueAt());
        time.instant=replaced.getRespondBy();service.get(id,"owner");assertEquals(2,settlementEvents().stream().filter(e->e.startsWith("SETTLEMENT_RESPONSE_OVERDUE")).count());
        assertEquals(2,settlementEvents().stream().filter(e->e.equals("SETTLEMENT_RESPONSE_NEEDED:tenant")).count());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"WAITING_HANDOVER","PROPOSED","DISPUTED","COMPLAINT"})
    void phase6eFinalCostsInvalidateOnlyOrdinaryReviewedProposalsAndReplayIsSilent(String status){
        readyClosure();if(!status.equals("WAITING_HANDOVER"))closureProposal("0");
        tx(()->{var s=closures.findById(id).orElseThrow();if(status.equals("DISPUTED"))s.setStatus("DISPUTED");if(status.equals("COMPLAINT"))s.setComplaintCaseId("test-complaint");closures.save(s);});
        int before=closures.findById(id).orElseThrow().getProposalVersion();
        var payload=new FinalCostsPayload(UUID.randomUUID().toString(),null,null,List.of(new IssueMonthlyInvoiceRequest.ExtraCharge("Chi phí bổ sung thực tế",BigDecimal.TEN)));
        service.issueFinalCosts(id,"owner",payload);service.issueFinalCosts(id,"owner",payload);
        assertThrows(AppException.class,()->service.issueFinalCosts(id,"owner",new FinalCostsPayload(payload.requestId(),null,null,List.of(new IssueMonthlyInvoiceRequest.ExtraCharge("Nội dung khác",BigDecimal.ONE)))));
        var s=closures.findById(id).orElseThrow();assertEquals(before+1,s.getProposalVersion());assertEquals("WAITING_HANDOVER",s.getStatus());assertNull(s.getRespondBy());
        assertEquals(status.equals("PROPOSED")||status.equals("DISPUTED")?1:0,settlementEvents().stream().filter(e->e.equals("SETTLEMENT_PROPOSAL_INVALIDATED:tenant")).count());
        assertEquals(1,handoverOutbox.findAll().stream().map(row->NOTICE_JSON.readTree(row.getPayload()))
                .filter(e->e.get("eventType").asText().equals("INVOICE_ISSUED")&&id.equals(e.get("data").get("contractId").asText())).count());
    }
    @Test void phase6eComplaintProposalApprovalAndDeadlineAreSilentButActualAcceptedRefundStillNotifies(){
        readyClosure();var p=closureProposal("100000");service.disputeClosure(id,"tenant",new DisputeClosurePayload(p.getProposalVersion(),"Không đồng ý khoản khấu trừ cần đối chiếu",List.of()));
        var c=complaints.forRental(rentalId,"tenant").getFirst();complaints.act(c.id(),"owner","COMMENT",complaintAction(c));c=complaints.get(c.id(),"tenant");c=complaints.act(c.id(),"tenant","ESCALATE",complaintAction(c));
        admin();c=complaints.adminAct(c.id(),"admin","CLAIM",complaintAction(c));
        var revision=closurePayload("50000",Math.toIntExact(c.sourceVersion()));
        complaints.adminAct(c.id(),"admin","DECIDE",new ComplaintAction(c.revision(),"Đã đối chiếu chứng cứ và đề xuất khấu trừ",null,"REVISE_SETTLEMENT",null,revision));
        var s=closures.findById(id).orElseThrow();time.instant=s.getRespondBy();service.get(id,"tenant");service.approveComplaintSettlement(id,c.id(),s.getProposalVersion(),"owner");
        assertEquals(List.of("SETTLEMENT_RESPONSE_NEEDED:tenant","SETTLEMENT_DISPUTED:owner"),settlementEvents());
        var accepted=service.acceptClosure(id,"tenant",new AcceptClosurePayload(s.getProposalVersion(),true,true));
        assertEquals(List.of("PAYMENT_REQUEST_CREATED:owner"),cashEvents(accepted.refundPayment().id()));
        assertEquals(List.of("SETTLEMENT_RESPONSE_NEEDED:tenant","SETTLEMENT_DISPUTED:owner"),settlementEvents());
    }
    @ParameterizedTest @CsvSource({"0,AWAITING_TRANSFER,owner","0,TRANSFER_REPORTED,owner","0,DISPUTED,owner","21000000,AWAITING_TRANSFER,tenant","21000000,TRANSFER_REPORTED,tenant","21000000,DISPUTED,tenant"})
    void phase6eClosureDeadlineUsesOnlyRootMarkerEvenForProofOrDispute(String deduction,PaymentStatus status,String payer){
        readyClosure();var proposal=closureProposal(deduction);var accepted=service.acceptClosure(id,"tenant",new AcceptClosurePayload(proposal.getProposalVersion(),true,true));
        String paymentId=accepted.refundPayment()!=null?accepted.refundPayment().id():accepted.balancePayment().id();
        tx(()->{var p=paymentRepo.findById(paymentId).orElseThrow();p.setStatus(status);paymentRepo.save(p);});
        time.instant=accepted.closure().getRefundDueAt();service.get(id,"owner");assertNull(closures.findById(id).orElseThrow().getOverdueLoggedAt());
        time.instant=time.now().plusSeconds(1);service.get(id,"tenant");service.get(id,"owner");
        assertEquals(List.of("SETTLEMENT_PAYMENT_OVERDUE:"+payer),settlementEvents().stream().filter(e->e.startsWith("SETTLEMENT_PAYMENT_OVERDUE")).toList());
        assertEquals(status==PaymentStatus.AWAITING_TRANSFER?PaymentStatus.OVERDUE:status,paymentRepo.findById(paymentId).orElseThrow().getStatus());
        assertEquals(List.of("PAYMENT_REQUEST_CREATED:"+payer),cashEvents(paymentId));assertNull(closures.findById(id).orElseThrow().getClosedAt());
    }
    PaymentRequest pendingObligation(PaymentType type,PaymentStatus status,Instant due,boolean refund){
        return paymentRepo.saveAndFlush(PaymentRequest.builder().contractId(id).rentalRequestId(rentalId).listingId(listingId)
                .payerId(refund?"owner":"tenant").payeeId(refund?"tenant":"owner").direction(refund?PaymentDirection.LANDLORD_TO_TENANT:PaymentDirection.TENANT_TO_LANDLORD)
                .type(type).status(status).totalAmount(BigDecimal.TEN).dueAt(due).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=ContractStatus.class,names={"CLOSED","TERMINATED"})
    void phase6eDeadlineSweepPagesStableIdsAcrossClosedParentsAndAllObligationDirections(ContractStatus status){
        tx(()->{var c=contracts.findById(id).orElseThrow();c.setStatus(status);c.setAgreedEarlyEndExclusive(LocalDate.of(2026,12,20));contracts.save(c);});
        List<PaymentRequest> pending=new ArrayList<>();tx(()->{for(int n=0;n<105;n++)pending.add(pendingObligation(n%3==0?PaymentType.INITIAL_PAYMENT_REFUND:PaymentType.DEPOSIT_ADJUSTMENT,
                n%2==0?PaymentStatus.AWAITING_TRANSFER:PaymentStatus.REJECTED,time.now().minusSeconds(1),n%3!=1));});
        service.scheduledSync();service.scheduledSync();
        for(var p:pending){assertEquals(PaymentStatus.OVERDUE,paymentRepo.findById(p.getId()).orElseThrow().getStatus());assertEquals(List.of("PAYMENT_OBLIGATION_OVERDUE:"+p.getPayerId()),cashEvents(p.getId()));}
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"BOUNDARY","PROOF","DISPUTED","CONFIRMED","CANCELLED","OVERDUE","CANCELLATION","WRONG_RENTAL","WRONG_PARTIES","CHILD","DEPOSIT"})
    void phase6eDeadlineSweepExcludesCancellationUntrustedLinksProofAndHistoricalOverdue(String scenario){
        PaymentRequest[] candidate={null};tx(()->{
            var c=contracts.findById(id).orElseThrow();c.setStatus(ContractStatus.TERMINATED);if(!scenario.equals("CANCELLATION"))c.setAgreedEarlyEndExclusive(LocalDate.of(2026,12,20));
            if(scenario.equals("CHILD"))c.setParentContractId("other-root");if(scenario.equals("DEPOSIT"))c.setAgreementKind("DEPOSIT");contracts.save(c);
            PaymentStatus status=switch(scenario){case "PROOF"->PaymentStatus.TRANSFER_REPORTED;case "DISPUTED"->PaymentStatus.DISPUTED;case "CONFIRMED"->PaymentStatus.CONFIRMED;case "CANCELLED"->PaymentStatus.CANCELLED;case "OVERDUE"->PaymentStatus.OVERDUE;default->PaymentStatus.AWAITING_TRANSFER;};
            var p=pendingObligation(PaymentType.INITIAL_PAYMENT_REFUND,status,scenario.equals("BOUNDARY")?time.now():time.now().minusSeconds(1),true);
            if(scenario.equals("WRONG_RENTAL"))p.setRentalRequestId("unrelated-rental");if(scenario.equals("WRONG_PARTIES"))p.setPayerId("outsider");candidate[0]=paymentRepo.saveAndFlush(p);
        });
        service.scheduledSync();var p=candidate[0];assertEquals(p.getStatus(),paymentRepo.findById(p.getId()).orElseThrow().getStatus());assertTrue(cashEvents(p.getId()).isEmpty());
        assertFalse(paymentAudits.existsByPaymentRequestIdAndEventType(p.getId(),PaymentEventType.OVERDUE));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=PaymentType.class,names={"DEPOSIT_ADJUSTMENT","INITIAL_PAYMENT_REFUND"})
    void phase6eDeadlineAuditDeduplicatesNoticeAfterLaterRejection(PaymentType type){
        tx(()->{var c=contracts.findById(id).orElseThrow();c.setStatus(ContractStatus.CLOSED);c.setAgreedEarlyEndExclusive(LocalDate.of(2026,12,20));contracts.save(c);});
        var p=pendingObligation(type,PaymentStatus.AWAITING_TRANSFER,time.now().minusSeconds(1),true);service.scheduledSync();
        tx(()->{var cash=paymentRepo.findById(p.getId()).orElseThrow();cash.setStatus(PaymentStatus.REJECTED);paymentRepo.save(cash);});service.scheduledSync();
        assertEquals(PaymentStatus.OVERDUE,paymentRepo.findById(p.getId()).orElseThrow().getStatus());assertEquals(List.of("PAYMENT_OBLIGATION_OVERDUE:owner"),cashEvents(p.getId()));
        assertEquals(2,paymentAudits.findByPaymentRequestIdOrderByCreatedAtAsc(p.getId()).stream().filter(e->e.getEventType()==PaymentEventType.OVERDUE).count());
    }
    @Test void phase6eDeadlineStatusAuditAndOutboxRollbackOnWriterFailureThenRetry(){
        tx(()->{var c=contracts.findById(id).orElseThrow();c.setStatus(ContractStatus.CLOSED);contracts.save(c);});
        var p=pendingObligation(PaymentType.DEPOSIT_ADJUSTMENT,PaymentStatus.AWAITING_TRANSFER,time.now().minusSeconds(1),false);
        var target=org.springframework.test.util.AopTestUtils.<com.hs.payment.notification.PaymentNotificationWriter>getUltimateTargetObject(paymentNoticeWriter);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test after overdue enqueue");}).when(target).enqueue(argThat(cash->p.getId().equals(cash.getId())),eq(com.hs.payment.notification.PaymentNotificationWriter.Event.PAYMENT_OBLIGATION_OVERDUE),anyString());
        service.scheduledSync();assertEquals(PaymentStatus.AWAITING_TRANSFER,paymentRepo.findById(p.getId()).orElseThrow().getStatus());assertTrue(cashEvents(p.getId()).isEmpty());assertFalse(paymentAudits.existsByPaymentRequestIdAndEventType(p.getId(),PaymentEventType.OVERDUE));
        doCallRealMethod().when(target).enqueue(any(),any(),anyString());service.scheduledSync();assertEquals(List.of("PAYMENT_OBLIGATION_OVERDUE:tenant"),cashEvents(p.getId()));
    }
    @Test void phase6eNoDepositClosesWithoutInventingAnObligationOrReceipt(){
        readyClosure();tx(()->{var d=deposits.findByRentalRequestId(rentalId).orElseThrow();d.setOriginalAmount(BigDecimal.ZERO);d.setHeldAmount(BigDecimal.ZERO);d.setRefundableAmount(BigDecimal.ZERO);deposits.save(d);});
        var proposal=closureProposal("0");long before=paymentNotices.count();
        service.acceptClosure(id,"tenant",new AcceptClosurePayload(proposal.getProposalVersion(),true,true));service.get(id,"owner");
        assertEquals(before,paymentNotices.count());assertEquals(List.of("SETTLEMENT_RESPONSE_NEEDED:tenant","LEASE_CLOSED:owner","LEASE_CLOSED:tenant"),settlementEvents());
        assertNull(closures.findById(id).orElseThrow().getRefundPaymentId());assertNull(closures.findById(id).orElseThrow().getBalancePaymentId());
    }
    @Test void phase6eUnilateralRecordedReturnIsAtomicAndDoesNotRepeatTheEarlierEndNotice(){
        var bill=invoices.findByContractIdAndPeriodIndex(id,0).orElseThrow();
        tx(()->{var c=contracts.findById(id).orElseThrow();c.setStatus(ContractStatus.TERMINATED);c.setTerminationCompletedAt(time.now().minusSeconds(100));
            c.setUnilateralTerminationSnapshot(support.json(List.of(UnilateralTerminationNotice.builder().id("ended-notice").invoiceId(bill.getId()).version(1)
                    .status(UnilateralTerminationNotice.Status.ENDED).notifiedAt(time.now().minusSeconds(86400)).endedAt(c.getTerminationCompletedAt()).build())));contracts.save(c);});
        var handover=new UnilateralTerminationPayloads.Handover(1,true,true);
        assertThrows(AppException.class,()->billing.completeUnilateralHandover(bill.getId(),"ended-notice","tenant",handover));
        assertThrows(IllegalStateException.class,()->tx(()->{billing.completeUnilateralHandover(bill.getId(),"ended-notice","owner",handover);throw new IllegalStateException("after handover enqueue");}));
        assertTrue(settlementEvents().isEmpty());assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
        assertTrue(NOTICE_JSON.readTree(contracts.findById(id).orElseThrow().getUnilateralTerminationSnapshot()).get(0).get("handoverAt").isNull());
        billing.completeUnilateralHandover(bill.getId(),"ended-notice","owner",handover);
        assertThrows(AppException.class,()->billing.completeUnilateralHandover(bill.getId(),"ended-notice","owner",handover));
        assertEquals(List.of("LEASE_RETURN_COMPLETED:tenant"),settlementEvents());assertTrue(terminationEvents().isEmpty());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"ADJUSTMENT","RENT_REFUND"})
    void phase6eObligationInsertionAuditLinesAndNotificationRollbackThenRetry(String kind){
        var source=pendingObligation(PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,time.now(),false);
        long rowsBefore=paymentRepo.count(),linesBefore=lines.count(),auditsBefore=paymentAudits.count(),noticesBefore=paymentNotices.count();
        Runnable create=()->{contracts.findByIdForUpdate(id).orElseThrow();
            if(kind.equals("ADJUSTMENT"))payments.createDepositAdjustment("test-amendment",id,rentalId,listingId,"owner","tenant",BigDecimal.ONE,time.now().plusSeconds(100));
            else payments.createAgreedRentRefundObligation(source,BigDecimal.ONE);
        };
        assertThrows(IllegalStateException.class,()->tx(()->{create.run();throw new IllegalStateException("after obligation enqueue");}));
        assertEquals(rowsBefore,paymentRepo.count());assertEquals(linesBefore,lines.count());assertEquals(auditsBefore,paymentAudits.count());assertEquals(noticesBefore,paymentNotices.count());
        tx(create);tx(create);
        var p=kind.equals("ADJUSTMENT")?paymentRepo.findByInvoiceId("test-amendment").orElseThrow():paymentRepo.findByOnboardingKey("REFUND:"+source.getId()).orElseThrow();
        assertEquals(List.of("PAYMENT_REQUEST_CREATED:"+p.getPayerId()),cashEvents(p.getId()));assertEquals(rowsBefore+1,paymentRepo.count());
    }
}
