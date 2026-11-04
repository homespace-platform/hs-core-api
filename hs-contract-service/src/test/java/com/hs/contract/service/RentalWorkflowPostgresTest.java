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
@EnabledIfEnvironmentVariable(named="HS_WORKFLOW_TEST_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_workflow_test")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_WORKFLOW_TEST_PG_URL}","spring.datasource.username=postgres",
    "spring.datasource.password=hs_workflow_test","spring.jpa.hibernate.ddl-auto=create-drop","spring.profiles.active=test"})
@Import({ContractLifecycleService.class,MonthlyBillingService.class,FinalRentCalculator.class,WorkflowDocumentService.class,WorkflowDocumentCommitter.class,WorkflowDocumentRetentionChecker.class,PaymentRequestService.class,DepositLedgerService.class,PaymentDisputeService.class,ComplaintCaseService.class,ComplaintEvidenceAccessChecker.class,RentalWorkflowSupport.class,PreTenancyResolutionService.class,RentalOnboardingService.class,MoveInHandoverService.class,MutualTerminationService.class,RentalWorkflowEvidenceAccessChecker.class})
class RentalWorkflowPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={ContractRepository.class,RentalRequestRepository.class,PaymentRequestRepository.class,StorageObjectRepository.class})
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
    @BeforeEach void signedFixture() throws Exception {when(workflowRenderer.render(any(),any(),any())).thenReturn("test PDF supplied by converter mock".getBytes(java.nio.charset.StandardCharsets.UTF_8));when(pdfEngine.addSignaturePage(any())).thenAnswer(c->c.getArgument(0));when(storageService.uploadDirect(any(byte[].class),anyString(),anyString(),any(),anyString(),anyString(),any(),anyString())).thenAnswer(c->{String file=UUID.randomUUID().toString();storage.saveAndFlush(StorageObject.builder().id(file).ownerId(c.getArgument(7)).originalName(c.getArgument(1)).objectKey("workflow/"+file).bucketName("test").contentType(c.getArgument(2)).sizeBytes((long)((byte[])c.getArgument(0)).length).referenceType(c.getArgument(4)).referenceId(c.getArgument(5)).purpose(c.getArgument(3)).visibility(c.getArgument(6)).status(StorageStatus.READY).build());var dto=mock(com.hs.storage.dto.response.StorageObjectResponse.class);when(dto.id()).thenReturn(file);return dto;});tx(()->{var c=contracts.findById(id).orElseThrow();c.setSignedAt(time.now().minusSeconds(30L*86400));contracts.save(c);
        var last=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();last.setStatus(MonthlyInvoiceStatus.DRAFT);invoices.save(last);});}
    RentalWorkflowPayloads.Action action(long revision){return new RentalWorkflowPayloads.Action(revision,"Đã đối chiếu nội dung và xác nhận thực tế",true,true);}
    RentalWorkflowPayloads.Termination early(long revision){return new RentalWorkflowPayloads.Termination(revision,LocalDate.of(2026,12,20),"Hai bên thỏa thuận kết thúc sớm", "Bàn giao phòng, chìa khóa và tài sản tại chỗ", "FULL_STARTED_PERIOD",List.of(),List.of());}
    RentalWorkflowPayloads.Handover move(long revision){return new RentalWorkflowPayloads.Handover(revision,time.now(),2,"Phòng đúng hiện trạng đã đối chiếu", "Biên bản nhận nhà thực tế đầu kỳ",BigDecimal.TEN,BigDecimal.ONE,List.of(new RentalWorkflowPayloads.Item("Chìa khóa",2,"Hoạt động tốt")),List.of());}
    void beforeLease(){tx(()->{var r=rentals.findById(rentalId).orElseThrow();r.setStatus(RentalRequestStatus.ACCEPTED);rentals.save(r);var c=contracts.findById(id).orElseThrow();c.setStatus(ContractStatus.DRAFT);c.setSignedAt(null);contracts.save(c);var l=properties.findById(listingId).orElseThrow();l.setStatus(ListingStatus.RESERVED);properties.save(l);});}

    RentalWorkflowView proposeCancel(){beforeLease();return cancellation.propose(rentalId,"owner",new RentalWorkflowPayloads.Cancellation(0L,"Không hoàn tất ký, đề nghị hoàn toàn bộ tiền",List.of()));}
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
    @Test void signedDepositIsPreservedAndSeparateRentAndDepositRefundsAreCreated(){
        var p=proposeCancel();tx(()->{var dc=contracts.saveAndFlush(Contract.builder().contractNumber("DC-"+UUID.randomUUID()).rentalRequestId(rentalId).listingId(listingId).landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("version").agreementKind("DEPOSIT").status(ContractStatus.ACTIVE).signedAt(time.now()).build());when(depositAgreements.find(rentalId)).thenReturn(Optional.of(dc));
            paymentRepo.saveAndFlush(PaymentRequest.builder().contractId(id).rentalRequestId(rentalId).listingId(listingId).payerId("tenant").payeeId("owner").type(PaymentType.INITIAL).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("5000000")).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).confirmedAt(time.now()).build());});
        var done=cancellation.act(rentalId,"tenant","ACCEPT",action(p.revision()));assertEquals(2,done.refundPayments().size());assertEquals(ContractStatus.ACTIVE,contracts.findById(depositAgreements.find(rentalId).orElseThrow().getId()).orElseThrow().getStatus());
        for(int i=0;i<2;i++){
            String refund=done.refundPayments().get(i).id();markRefundReported(refund);payments.confirmReceipt(refund,"tenant");
            if(i==0)assertAwaitingRefund();
        }
        assertEquals(ContractStatus.CANCELLED,contracts.findById(id).orElseThrow().getStatus());
        assertEquals(ContractStatus.ACTIVE,contracts.findById(depositAgreements.find(rentalId).orElseThrow().getId()).orElseThrow().getStatus());
        verify(parking,times(1)).releaseReservationsForRequest(rentalId);
    }
    @Test void cancellationEscalatesToExistingCaseAndKeepsSigningPaused(){var p=proposeCancel();var next=cancellation.act(rentalId,"tenant","ESCALATE",action(p.revision()));assertEquals("DISPUTED",next.status());assertNotNull(next.complaintId());assertNotNull(rentals.findById(rentalId).orElseThrow().getResolutionWorkflowId());assertThrows(AppException.class,()->cancellation.propose(rentalId,"owner",new RentalWorkflowPayloads.Cancellation(next.revision(),"Đề xuất khác trong khi có khiếu nại",List.of())));}
    @Test void withdrawalUnpausesSigningAndPreservesPriorProposal(){var p=proposeCancel();var done=cancellation.act(rentalId,"owner","WITHDRAW",action(p.revision()));assertEquals("WITHDRAWN",done.status());assertNull(rentals.findById(rentalId).orElseThrow().getResolutionWorkflowId());assertEquals(2,done.history().size());}
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
        assertThrows(AppException.class,()->termination.act(rentalId,"tenant","ACCEPT",action(p.revision())));
        var agreed=termination.act(rentalId,"owner","ACCEPT",action(p.revision()));assertEquals("AGREED",agreed.status());
        var c=contracts.findById(id).orElseThrow();assertEquals(LocalDate.of(2026,12,20),c.getAgreedEarlyEndExclusive());assertEquals(3,c.getAgreedEarlyBillingMonths());assertNull(c.getEffectiveLeaseMonths());assertEquals(3,rentals.findById(rentalId).orElseThrow().getLeaseMonths());
        verify(parking,never()).releaseReservationsForContract(id);
        assertThrows(AppException.class,()->termination.act(rentalId,"tenant","CONFIRM_RETURN",action(agreed.revision())));
        clock("2026-12-20T10:00:00");service.get(id,"owner");assertEquals(ContractStatus.EXPIRED,contracts.findById(id).orElseThrow().getStatus());
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
    @Test void verifiedTerminationDocumentAppliesAgreementOnceAndNeverMarksMoneyReceived(){var w=termination.propose(rentalId,"tenant",early(0));var doc=workflowDocuments.prepare(rentalId,"early-exit","owner",w.revision());var c=contracts.findById(doc.signingContractId()).orElseThrow();verifiedDocument(c);workflowDocuments.bothSigned(c);assertEquals("AGREED",termination.get(rentalId,"tenant").status());assertEquals(LocalDate.of(2026,12,20),contracts.findById(id).orElseThrow().getAgreedEarlyEndExclusive());assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());workflowDocuments.bothSigned(c);}
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

}
