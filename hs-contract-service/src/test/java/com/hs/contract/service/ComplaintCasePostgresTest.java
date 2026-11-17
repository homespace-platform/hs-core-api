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
@EnabledIfEnvironmentVariable(named="HS_COMPLAINT_TEST_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_phase04_test")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_COMPLAINT_TEST_PG_URL}","spring.datasource.username=postgres",
    "spring.datasource.password=hs_phase04_test","spring.jpa.hibernate.ddl-auto=create-drop","spring.profiles.active=test"})
@Import({com.hs.contract.notification.AgreementSigningNotificationWriter.class,com.hs.contract.notification.RenewalNotificationWriter.class,com.hs.contract.notification.TerminationNotificationWriter.class,com.hs.contract.notification.InvoiceNotificationWriter.class,com.hs.listing.notification.RentalRequestNotificationWriter.class,com.hs.payment.notification.PaymentNotificationWriter.class,ContractLifecycleService.class,PaymentRequestService.class,DepositLedgerService.class,PaymentDisputeService.class,ComplaintCaseService.class,ComplaintEvidenceAccessChecker.class})
class ComplaintCasePostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={com.hs.contract.notification.ContractNotificationOutboxRepository.class,com.hs.listing.notification.RentalRequestNotificationOutboxRepository.class,com.hs.payment.notification.PaymentNotificationOutboxRepository.class,ContractRepository.class,RentalRequestRepository.class,PaymentRequestRepository.class,StorageObjectRepository.class})
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
    @MockitoBean com.hs.contract.notification.ComplaintNotificationWriter complaintNotifications;
    @MockitoBean com.hs.contract.deposit.DepositAgreementService depositAgreements;
    @MockitoBean RentalWorkflowSupport workflowSupport;
    @MockitoBean ContractTermsResolver terms;
    @MockitoBean ListingStatusService listings;
    @MockitoBean ParkingReservationService parking;
    @MockitoBean BankAccountService banks;
    @MockitoBean VietQrProvider qr;
    @MockitoBean TransferReferenceGenerator references;
    @MockitoBean PaymentProofUploadSessionService uploadSessions;
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
    ContractClosure proposal(String deduction){
        clock("2027-01-14T10:00:00");
        return service.proposeClosure(id,"owner",new ClosurePayload(null,null,new BigDecimal(deduction),"Đã bàn giao và thống nhất khấu trừ theo hiện trạng",true)).closure();
    }
    void accept(int version){service.acceptClosure(id,"tenant",new AcceptClosurePayload(version,true,true));}
    ContractRenewal request(int months){return service.requestRenewal(id,"tenant",new RenewalPayload(months,"Tiếp tục thuê theo điều khoản đã ký")).renewals().stream().filter(r->"REQUESTED".equals(r.getStatus())).findFirst().orElseThrow();}
    Contract approve(ContractRenewal renewal){service.decideRenewal(id,renewal.getId(),"owner",true);return contracts.findById(renewals.findById(renewal.getId()).orElseThrow().getAgreementContractId()).orElseThrow();}
    void signed(Contract agreement){tx(()->{
        for(var role:SignerRole.values())signatures.saveAndFlush(SignatureRequest.builder().contractId(agreement.getId()).revisionId(agreement.getCurrentRevisionId())
            .sourceDocumentId("test-source").docId(UUID.randomUUID().toString()).signerRole(role).signerUserId(role==SignerRole.TENANT?"tenant":"owner").status(SignatureRequestStatus.SIGNED).build());
        documents.saveAndFlush(ContractDocument.builder().contractId(agreement.getId()).revisionId(agreement.getCurrentRevisionId()).templateVersionId("template-v1")
            .documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.SIGNED_FINAL).status(DocumentGenerationStatus.READY).build());
    });}
    String proof(String paymentId,String actor){String file=UUID.randomUUID().toString();tx(()->storage.saveAndFlush(StorageObject.builder().id(file).ownerId(actor)
        .originalName("proof.pdf").objectKey("test/"+file).bucketName("test-only").contentType("application/pdf").sizeBytes(100L)
        .purpose(StoragePurpose.PAYMENT_PROOF).visibility(StorageVisibility.PRIVATE).status(StorageStatus.READY)
        .referenceType("PAYMENT_REQUEST").referenceId(paymentId).build()));return file;}


    @AfterEach void clear(){UserContextHolder.clear();SecurityContextHolder.clearContext();}
    void admin(String actor){UserContextHolder.set(new UserContext(actor,null));SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(actor,"n/a",List.of(new SimpleGrantedAuthority("ADMIN"))));}
    ComplaintAction action(ComplaintView c){return new ComplaintAction(c.revision(),"Đã đọc và phản hồi đầy đủ hồ sơ",null,null,null,null);}
    ComplaintView open(ComplaintCategory category){return complaints.open(rentalId,"tenant",new ComplaintOpen(category,"Phòng không đúng điều kiện đã cam kết",null,false));}
    ComplaintView escalate(ComplaintView c){complaints.act(c.id(),"owner","COMMENT",action(c));c=complaints.get(c.id(),"tenant");return complaints.act(c.id(),"tenant","ESCALATE",action(c));}
    ComplaintView claim(ComplaintView c){admin("admin");return complaints.adminAct(c.id(),"admin","CLAIM",action(c));}
    ComplaintView disputedClosure(){proposal("100000");var close=closures.findById(id).orElseThrow();service.disputeClosure(id,"tenant",new DisputeClosurePayload(close.getProposalVersion(),"Không đồng ý khoản khấu trừ kính đã vỡ sẵn",List.of()));return complaints.forRental(rentalId,"tenant").getFirst();}
    ComplaintView adminConclusion(ComplaintView c){
        c=claim(escalate(c));
        c=complaints.adminAct(c.id(),"admin","DECIDE",new ComplaintAction(c.revision(),"Đã đối chiếu và hướng dẫn hai bên xử lý",null,"RECORD_FINDING",null,null));
        return complaints.act(c.id(),"tenant","CLOSE",action(c));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(ComplaintCategory.class)
    void partiesCanOpenCategoriesButOnlyActualClosureObjectionCanOpenSettlementCase(ComplaintCategory category){
        if(category==ComplaintCategory.DEPOSIT_SETTLEMENT){assertThrows(AppException.class,()->open(category));return;}
        var c=open(category);assertEquals(DisputeCaseStatus.OPEN,c.status());assertEquals(time.now().plusSeconds(48*3600),c.respondBy());assertEquals("owner",c.responseDueFor());
        assertThrows(AppException.class,()->open(category));assertThrows(AppException.class,()->complaints.get(c.id(),"stranger"));
        assertThrows(AppException.class,()->complaints.open(rentalId,"stranger",new ComplaintOpen(category,"Không thuộc hồ sơ thuê của tôi",null,false)));
        assertEquals(1,c.events().size());
    }
    @Test void initialDeadlineIsNotExtendedByOpeningPartysRepeatedCommentsAndCutoffIsInclusive(){
        var c=open(ComplaintCategory.REPAIR);var due=c.respondBy();
        c=complaints.act(c.id(),"tenant","COMMENT",action(c));assertEquals(due,c.respondBy());
        time.instant=due;assertTrue(complaints.get(c.id(),"tenant").overdue());
        c=complaints.act(c.id(),"tenant","ESCALATE",action(c));assertEquals("WAITING_ADMIN",c.stage());
        assertEquals(0,ledger.forRental(rentalId).heldAmount().compareTo(principal));assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());
    }
    @Test void urgentSafetyCanEscalateImmediatelyButOrdinaryTermsCannot(){
        var c=complaints.open(rentalId,"tenant",new ComplaintOpen(ComplaintCategory.SAFETY,"Sự cố an toàn cần HomeSpace hỗ trợ khẩn",null,true));assertEquals(DisputeCaseStatus.ESCALATED,c.status());
        assertThrows(AppException.class,()->complaints.open(rentalId,"tenant",new ComplaintOpen(ComplaintCategory.TERMS,"Nội dung điều khoản đang chưa thống nhất",null,true)));
    }
    @Test void simpleExchangeDoesNotExposeAgreementOrFinancialActions(){
        var c=open(ComplaintCategory.CONDITION);var reviewed=c;
        for(String action:List.of("PROPOSE","ACCEPT","DECLINE","APPROVE_SETTLEMENT","CONFIRM_COMPLETE","REJECT_DECISION"))
            assertThrows(AppException.class,()->complaints.act(reviewed.id(),"tenant",action,action(reviewed)));
        assertEquals(List.of("COMMENT","ESCALATE","CLOSE"),c.allowedActions());
        c=complaints.act(c.id(),"tenant","ESCALATE",action(c));assertEquals("WAITING_ADMIN",c.stage());
    }
    @Test void withdrawAndReopenKeepHistoryAndCannotCreateSecondActiveIssue(){
        var c=open(ComplaintCategory.TERMS);var first=c;
        assertThrows(AppException.class,()->complaints.act(first.id(),"owner","WITHDRAW",action(first)));
        c=complaints.act(c.id(),"tenant","WITHDRAW",action(c));assertEquals("WITHDRAWN",c.stage());
        c=complaints.act(c.id(),"tenant","REOPEN",action(c));assertEquals(DisputeCaseStatus.OPEN,c.status());assertEquals(3,c.events().size());
        var duplicate=c;assertThrows(AppException.class,()->open(ComplaintCategory.TERMS));assertThrows(AppException.class,()->complaints.act(duplicate.id(),"tenant","REOPEN",action(duplicate)));
    }
    @Test void adminMustClaimCannotBeAPartyAndRequestsInfoFromRealParticipantOnly(){
        var c=escalate(open(ComplaintCategory.REPAIR));admin("admin");var noClaim=c;
        assertThrows(AppException.class,()->complaints.adminAct(noClaim.id(),"admin","DECIDE",new ComplaintAction(noClaim.revision(),"Kết luận khi chưa tiếp nhận hồ sơ",null,"RECORD_FINDING",null,null)));
        admin("tenant");assertThrows(AppException.class,()->complaints.adminAct(noClaim.id(),"tenant","CLAIM",action(noClaim)));
        c=claim(c);var claimed=c;
        assertThrows(AppException.class,()->complaints.adminAct(claimed.id(),"admin","REQUEST_INFO",new ComplaintAction(claimed.revision(),"Yêu cầu gửi thêm chứng cứ hiện trạng",null,null,"stranger",null)));
        c=complaints.adminAct(c.id(),"admin","REQUEST_INFO",new ComplaintAction(c.revision(),"Người thuê bổ sung ảnh hiện trạng phòng",null,null,"tenant",null));
        var due=c.respondBy();c=complaints.act(c.id(),"owner","COMMENT",action(c));assertEquals(due,c.respondBy());assertEquals("WAITING_INFO",c.stage());
        c=complaints.act(c.id(),"tenant","COMMENT",action(c));assertEquals("UNDER_REVIEW",c.stage());assertEquals("ADMIN",c.responseDueFor());
    }
    @Test void retryingAdminClaimPreservesPendingInformationAndItsOriginalDeadline(){
        var c=claim(escalate(open(ComplaintCategory.REPAIR)));
        c=complaints.adminAct(c.id(),"admin","REQUEST_INFO",new ComplaintAction(c.revision(),"Người thuê bổ sung ảnh khóa cửa hiện tại",null,null,"tenant",null));
        var expected=c;time.instant=c.respondBy().plusSeconds(1);
        c=complaints.adminAct(c.id(),"admin","CLAIM",action(c));
        assertEquals(expected.revision(),c.revision());assertEquals(expected.respondBy(),c.respondBy());
        assertEquals("WAITING_INFO",c.stage());assertEquals("tenant",c.responseDueFor());assertTrue(c.overdue());
        assertEquals(expected.events().size(),c.events().size());
        assertFalse(c.allowedActions().contains("CLAIM"));assertTrue(c.allowedActions().contains("DECIDE"));
        admin("next-admin");c=complaints.adminAct(c.id(),"next-admin","CLAIM",action(c));
        assertEquals("next-admin",c.assignedTo());assertEquals(expected.revision()+1,c.revision());
        assertEquals("WAITING_INFO",c.stage());assertEquals("tenant",c.responseDueFor());assertTrue(c.overdue());
        assertEquals(expected.respondBy(),c.respondBy());assertEquals(expected.events().size()+1,c.events().size());
        assertFalse(c.allowedActions().contains("CLAIM"));assertTrue(c.allowedActions().contains("DECIDE"));
        assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());
        assertEquals(0,ledger.forRental(rentalId).heldAmount().compareTo(principal));
    }
    @Test void partiesCanCloseAfterFirstFindingAndReopenWithoutChangingMoney(){
        var c=adminConclusion(open(ComplaintCategory.REPAIR));assertEquals("CLOSED",c.stage());assertEquals(DisputeCaseStatus.RESOLVED,c.status());
        c=complaints.act(c.id(),"tenant","REOPEN",action(c));assertEquals(DisputeCaseStatus.OPEN,c.status());assertNull(c.outcome());
        assertEquals(0,ledger.forRental(rentalId).heldAmount().compareTo(principal));assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());
    }
    @Test void settlementComplaintCannotBeBypassedByNormalProposalOrAcceptance(){
        var c=disputedClosure();var original=closures.findById(id).orElseThrow();
        assertFalse(service.get(id,"owner").canProposeClosure());
        assertThrows(AppException.class,()->proposal("0"));assertThrows(AppException.class,()->accept(original.getProposalVersion()));
        assertEquals(DisputeCaseStatus.OPEN,c.status());assertEquals(DepositStatus.DISPUTED,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
    }
    @Test void adminCannotReviseSettlementFromComplaint(){
        var c=claim(escalate(disputedClosure()));int version=closures.findById(id).orElseThrow().getProposalVersion();
        var p=new ClosurePayload(null,null,new BigDecimal("50000"),"Không được sửa quyết toán qua khiếu nại",true,List.of(),version);
        assertThrows(AppException.class,()->complaints.adminAct(c.id(),"admin","DECIDE",new ComplaintAction(c.revision(),"Đã xem chứng cứ và muốn sửa quyết toán",null,"REVISE_SETTLEMENT",null,p)));
        assertEquals(version,closures.findById(id).orElseThrow().getProposalVersion());
        assertEquals(0,ledger.forRental(rentalId).heldAmount().compareTo(principal));assertTrue(paymentRepo.findByContractIdAndType(id,PaymentType.DEPOSIT_REFUND).isEmpty());
    }
    @Test void closedComplaintCannotBeUsedToAcceptAnUnreviewedFinancialVersion(){
        var c=adminConclusion(disputedClosure());int version=Math.toIntExact(c.sourceVersion());
        assertThrows(AppException.class,()->accept(version));assertEquals("DISPUTED",closures.findById(id).orElseThrow().getStatus());
        c=complaints.act(c.id(),"owner","REOPEN",action(c));assertEquals(DisputeCaseStatus.OPEN,c.status());
    }
    @Test void failedAuditRollsBackOpeningAndSourceObjectionTogether(){
        proposal("100000");int version=closures.findById(id).orElseThrow().getProposalVersion();
        doThrow(new IllegalStateException("Audit unavailable")).when(caseEvents).save(any(DisputeCaseEvent.class));
        assertThrows(IllegalStateException.class,()->service.disputeClosure(id,"tenant",new DisputeClosurePayload(version,"Không đồng ý khoản khấu trừ hiện tại",List.of())));
        assertEquals("PROPOSED",closures.findById(id).orElseThrow().getStatus());assertTrue(cases.findByActiveKey(ContractLifecycleService.settlementComplaintKey(rentalId)).isEmpty());
    }
    @Test void failedConclusionAuditRollsBackClosureOfSupportCase(){
        var c=claim(escalate(disputedClosure()));int version=closures.findById(id).orElseThrow().getProposalVersion();
        doThrow(new IllegalStateException("Audit unavailable")).when(caseEvents).save(any(DisputeCaseEvent.class));
        assertThrows(IllegalStateException.class,()->complaints.adminAct(c.id(),"admin","DECIDE",new ComplaintAction(c.revision(),"Đã đọc và đối chiếu chứng cứ hai bên",null,"RECORD_FINDING",null,null)));
        assertEquals(version,closures.findById(id).orElseThrow().getProposalVersion());assertEquals("UNDER_REVIEW",complaints.adminGet(c.id(),"admin").stage());
    }
    @Test void privateEvidenceMustBeAttachedOwnedAndBoundBeforeSharingAndCannotBeDeleted(){
        var c=open(ComplaintCategory.CONDITION);String file=UUID.randomUUID().toString();
        tx(()->storage.saveAndFlush(StorageObject.builder().id(file).ownerId("tenant").originalName("condition.pdf").objectKey("test/"+file).bucketName("test-only").sizeBytes(100L).contentType("application/pdf")
            .status(StorageStatus.READY).purpose(StoragePurpose.GENERAL).visibility(StorageVisibility.PRIVATE).referenceType("RENTAL_COMPLAINT_EVIDENCE").referenceId(rentalId).build()));
        assertFalse(access.canAccess("owner",storage.findById(file).orElseThrow()));
        c=complaints.act(c.id(),"tenant","COMMENT",new ComplaintAction(c.revision(),"Bổ sung ảnh phòng trước khi nhận nhà",List.of(file),null,null,null));
        assertTrue(access.canAccess("owner",storage.findById(file).orElseThrow()));assertFalse(access.canAccess("stranger",storage.findById(file).orElseThrow()));assertTrue(access.preventsDeletion(storage.findById(file).orElseThrow()));
        var current=c;assertThrows(AppException.class,()->complaints.act(current.id(),"owner","COMMENT",new ComplaintAction(current.revision(),"Không được mạo nhận chứng cứ người thuê",List.of(file),null,null,null)));
    }
    @Test void paymentQueueAndRoutesRemainIsolatedFromRentalComplaints(){
        var c=open(ComplaintCategory.REPAIR);admin("admin");
        assertTrue(disputes.adminList("admin",null,1,50).getResult().isEmpty());
        assertThrows(AppException.class,()->disputes.adminGet(c.id(),"admin"));
        assertTrue(complaints.adminList("admin",DisputeCaseStatus.OPEN,ComplaintCategory.REPAIR,false,1,20).getResult().stream().anyMatch(row->row.id().equals(c.id())));
        assertTrue(complaints.adminList("admin",null,null,true,1,20).getResult().stream().noneMatch(row->row.id().equals(c.id())));
        time.instant=c.respondBy();assertTrue(complaints.adminList("admin",null,null,true,1,20).getResult().stream().anyMatch(row->row.id().equals(c.id())));
    }
    @Test void twoSimultaneousOpensOnlyCreateOneActiveIssue(){
        assertEquals(List.of(0,6076),race(()->open(ComplaintCategory.REPAIR)));
        assertEquals(1,cases.findBySubjectTypeAndRentalRequestIdOrderByOpenedAtDesc("RENTAL",rentalId).size());
    }
    @Test void twoAdminClaimsAndDecisionsUseFreshRevisionAndNeverApplyTwice(){
        var c=escalate(open(ComplaintCategory.REPAIR));
        assertEquals(List.of(0,6075),race(()->{admin("admin");complaints.adminAct(c.id(),"admin","CLAIM",action(c));}));
        admin("admin");var claimed=complaints.adminGet(c.id(),"admin");
        var p=new ComplaintAction(claimed.revision(),"Kết luận theo ảnh và phản hồi hiện trạng",null,"RECORD_FINDING",null,null);
        assertEquals(List.of(0,6075),race(()->{admin("admin");complaints.adminAct(c.id(),"admin","DECIDE",p);}));
        assertEquals(1,caseEvents.findByCaseIdOrderByRevisionAsc(c.id()).stream().filter(e->e.getAction().equals("DECIDE")).count());
    }
    @Test void oldPartyVersionCannotOverwriteACommentAndConcurrentSubmissionsApplyOnce(){
        var c=open(ComplaintCategory.CONDITION);var original=c;
        c=complaints.act(c.id(),"owner","COMMENT",action(c));
        assertEquals(6075,assertThrows(AppException.class,()->complaints.act(original.id(),"tenant","COMMENT",action(original))).getCode());
        var fresh=c;assertEquals(List.of(0,6075),race(()->complaints.act(fresh.id(),"tenant","COMMENT",action(fresh))));
    }
    @Test void reopeningStartsFreshResponseRoundAndOnlyItsOpenerMayWithdraw(){
        var c=open(ComplaintCategory.CONDITION);c=complaints.act(c.id(),"owner","COMMENT",action(c));
        c=complaints.act(c.id(),"tenant","WITHDRAW",action(c));c=complaints.act(c.id(),"owner","REOPEN",action(c));var current=c;
        assertThrows(AppException.class,()->complaints.act(current.id(),"tenant","WITHDRAW",action(current)));
        assertTrue(current.allowedActions().contains("ESCALATE"));
        assertTrue(complaints.act(c.id(),"owner","WITHDRAW",action(c)).events().size()>3);
    }
    @Test void aNewSettlementVersionOpensANewSupportCaseAndPreservesOldConclusion(){
        var c=adminConclusion(disputedClosure());int oldVersion=Math.toIntExact(c.sourceVersion());
        var fresh=proposal("50000");assertTrue(fresh.getProposalVersion()>oldVersion);
        service.disputeClosure(id,"tenant",new DisputeClosurePayload(fresh.getProposalVersion(),"Chưa đồng ý phiên bản mới vì còn sai hiện trạng",List.of()));
        assertEquals(DisputeCaseStatus.RESOLVED,complaints.get(c.id(),"tenant").status());
        var next=complaints.forRental(rentalId,"tenant").stream().filter(row->row.status()!=DisputeCaseStatus.RESOLVED).findFirst().orElseThrow();
        assertNotEquals(c.id(),next.id());assertEquals((long)fresh.getProposalVersion(),next.sourceVersion());
        assertThrows(AppException.class,()->accept(fresh.getProposalVersion()));
    }
    @Test void twoAdminConclusionsAreTheHardLimitAcrossDiscussionAndReassignment(){
        var c=claim(escalate(open(ComplaintCategory.REPAIR)));
        c=complaints.adminAct(c.id(),"admin","DECIDE",new ComplaintAction(c.revision(),"Kết luận lần đầu: đề nghị chủ nhà sửa khóa",null,"RECORD_FINDING",null,null));
        assertEquals(DisputeCaseStatus.OPEN,c.status());assertEquals("FOLLOW_UP",c.stage());assertEquals(1,c.adminReviewCount());assertFalse(c.finalClosed());
        assertTrue(complaints.adminGet(c.id(),"admin").allowedActions().isEmpty());
        var first=c;assertThrows(AppException.class,()->complaints.adminAct(first.id(),"admin","DECIDE",action(first)));
        c=complaints.act(c.id(),"tenant","COMMENT",action(c));c=complaints.act(c.id(),"owner","COMMENT",action(c));
        assertEquals(1,c.adminReviewCount());c=complaints.act(c.id(),"owner","ESCALATE",action(c));assertNull(c.assignedTo());
        admin("second-admin");c=complaints.adminAct(c.id(),"second-admin","CLAIM",action(c));assertEquals(1,c.adminReviewCount());
        c=complaints.adminAct(c.id(),"second-admin","DECIDE",new ComplaintAction(c.revision(),"Kết luận cuối: đã kiểm tra toàn bộ phản hồi",null,"RECORD_FINDING",null,null));
        assertEquals(DisputeCaseStatus.RESOLVED,c.status());assertEquals("CLOSED",c.stage());assertTrue(c.finalClosed());assertEquals(2,c.adminReviewCount());
        assertTrue(c.allowedActions().isEmpty());assertTrue(complaints.adminGet(c.id(),"second-admin").allowedActions().isEmpty());
        var last=c;
        for(String actor:List.of("tenant","owner"))for(String action:List.of("COMMENT","CLOSE","ESCALATE","REOPEN"))
            assertThrows(AppException.class,()->complaints.act(last.id(),actor,action,action(last)));
        for(String action:List.of("CLAIM","COMMENT","DECIDE"))assertThrows(AppException.class,()->complaints.adminAct(last.id(),"second-admin",action,action(last)));
        assertEquals(2,caseEvents.findByCaseIdOrderByRevisionAsc(c.id()).stream().filter(e->"ADMIN".equals(e.getActorRole())&&"DECIDE".equals(e.getAction())).count());
        assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());assertEquals(0,ledger.forRental(rentalId).heldAmount().compareTo(principal));
    }
    @Test void closeAndReopenDoNotResetFirstAdminConclusion(){
        var c=adminConclusion(open(ComplaintCategory.REPAIR));assertEquals(1,c.adminReviewCount());
        c=complaints.act(c.id(),"owner","REOPEN",action(c));assertEquals(1,c.adminReviewCount());
        c=claim(escalate(c));c=complaints.adminAct(c.id(),"admin","DECIDE",new ComplaintAction(c.revision(),"Kết luận lần hai sau khi hồ sơ mở lại",null,"RECORD_FINDING",null,null));
        assertEquals(2,c.adminReviewCount());assertTrue(c.finalClosed());
    }
    @Test void concurrentSecondConclusionsCommitExactlyOneFinalDecision(){
        var c=adminConclusion(open(ComplaintCategory.CONDITION));c=complaints.act(c.id(),"tenant","REOPEN",action(c));c=claim(escalate(c));
        var reviewed=c;var p=new ComplaintAction(c.revision(),"Đã kiểm tra lại lần cuối toàn bộ chứng cứ",null,"RECORD_FINDING",null,null);
        assertEquals(List.of(0,6075),race(()->{admin("admin");complaints.adminAct(reviewed.id(),"admin","DECIDE",p);}));
        c=complaints.get(c.id(),"tenant");assertTrue(c.finalClosed());assertEquals(2,c.adminReviewCount());
    }
    @Test void failedSecondConclusionAuditCannotCloseCaseOrConsumeBudget(){
        var c=adminConclusion(open(ComplaintCategory.CONDITION));c=complaints.act(c.id(),"owner","REOPEN",action(c));c=claim(escalate(c));var reviewed=c;
        doThrow(new IllegalStateException("Audit unavailable")).when(caseEvents).save(any(DisputeCaseEvent.class));
        assertThrows(IllegalStateException.class,()->complaints.adminAct(reviewed.id(),"admin","DECIDE",new ComplaintAction(reviewed.revision(),"Kết luận cuối phải được ghi lịch sử nguyên vẹn",null,"RECORD_FINDING",null,null)));
        var after=complaints.get(c.id(),"tenant");assertEquals(1,after.adminReviewCount());assertEquals("UNDER_REVIEW",after.stage());assertEquals(reviewed.revision(),after.revision());
        assertFalse(after.finalClosed());assertTrue(cases.findByActiveKey("RENTAL:"+rentalId+":CONDITION").isPresent());
    }
    @Test void otherPartyCanCloseIssueWithoutAdminAndSimultaneousCloseCannotDoubleApply(){
        var c=open(ComplaintCategory.REPAIR);var reviewed=c;
        assertEquals(List.of(0,6075),race(()->complaints.act(reviewed.id(),"owner","CLOSE",action(reviewed))));
        c=complaints.get(c.id(),"tenant");assertEquals(DisputeCaseStatus.RESOLVED,c.status());assertEquals(0,c.adminReviewCount());
        assertEquals(1,c.events().stream().filter(e->"CLOSE".equals(e.action())).count());
    }
    @Test void legacyActiveCaseWithTwoConclusionsIsFinalizedOnceAndRetainsAudit(){
        var c=claim(escalate(open(ComplaintCategory.REPAIR)));String caseId=c.id();
        tx(()->{var d=cases.findById(caseId).orElseThrow();long revision=d.getRevision();
            for(int i=1;i<=2;i++)caseEvents.saveAndFlush(DisputeCaseEvent.builder().caseId(caseId).revision(++revision).actorId("admin").actorRole("ADMIN").action("DECIDE").reason("Kết luận từ phiên bản xử lý cũ").occurredAt(time.now()).build());
            d.setRevision(revision);d.setComplaintStage("FOLLOW_UP");cases.saveAndFlush(d);});
        c=complaints.get(caseId,"tenant");assertTrue(c.finalClosed());assertEquals(2,c.adminReviewCount());assertEquals(DisputeCaseStatus.RESOLVED,c.status());
        long revision=c.revision();assertEquals(revision,complaints.get(caseId,"owner").revision());
        assertEquals(1,c.events().stream().filter(e->"FINALIZED".equals(e.action())).count());
        assertTrue(cases.findByActiveKey("RENTAL:"+rentalId+":REPAIR").isEmpty());
        assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());
    }
    @Test void malformedOrForeignEvidenceCannotAttachOrChangeCase(){
        var c=open(ComplaintCategory.REPAIR);String file=UUID.randomUUID().toString();
        tx(()->storage.saveAndFlush(StorageObject.builder().id(file).ownerId("tenant").originalName("wrong.pdf").objectKey("test/"+file).bucketName("test-only").sizeBytes(100L).contentType("application/pdf")
            .status(StorageStatus.READY).purpose(StoragePurpose.GENERAL).visibility(StorageVisibility.PUBLIC).referenceType("RENTAL_COMPLAINT_EVIDENCE").referenceId(rentalId).build()));
        var p=new ComplaintAction(c.revision(),"Chứng cứ public chưa được phép chia sẻ vào hồ sơ",List.of(file),null,null,null);
        assertThrows(AppException.class,()->complaints.act(c.id(),"tenant","COMMENT",p));assertEquals(c.revision(),complaints.get(c.id(),"tenant").revision());
    }
    @Test void configuredDeadlineAffectsNewActionsWithoutRewritingEarlierDeadline(){
        var original=open(ComplaintCategory.REPAIR);
        org.springframework.test.util.ReflectionTestUtils.setField(complaints,"responseHours",12L);
        try{
            var another=open(ComplaintCategory.CONDITION);assertEquals(time.now().plusSeconds(12*3600),another.respondBy());
            assertEquals(original.respondBy(),complaints.get(original.id(),"tenant").respondBy());
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(complaints,"responseHours",48L);}
    }
    @Test void requestWithoutContractHasFullComplaintFlowAndKeepsItsOriginAfterContractIsCreated(){
        String requestId=UUID.randomUUID().toString();
        tx(()->rentals.saveAndFlush(RentalRequest.builder().id(requestId).listing(properties.findById(listingId).orElseThrow()).ownerId("owner").renterId("tenant")
            .renterName("Tenant").renterPhone("0900000000").status(RentalRequestStatus.PENDING).moveInDate(time.today()).leaseMonths(3)
            .monthlyRentPrice(new BigDecimal("5000000")).depositAmount(principal).build()));
        var c=complaints.open(requestId,"tenant",new ComplaintOpen(ComplaintCategory.HANDOVER,"Muốn xác minh lại lịch bàn giao nhà",List.of(),false));assertNull(c.contractId());
        tx(()->contracts.saveAndFlush(Contract.builder().contractNumber("LATER-"+UUID.randomUUID()).rentalRequestId(requestId).listingId(listingId)
            .landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("template-v1").status(ContractStatus.DRAFT).build()));
        c=complaints.act(c.id(),"owner","COMMENT",action(c));assertNull(c.contractId());assertEquals(DisputeCaseStatus.OPEN,c.status());
        c=complaints.act(c.id(),"tenant","ESCALATE",action(c));assertEquals(DisputeCaseStatus.ESCALATED,c.status());
    }
    @Test void conclusionDoesNotAcceptMoneyAndOwnerMustProposeThroughSettlementFlow(){
        var c=adminConclusion(disputedClosure());int oldVersion=Math.toIntExact(c.sourceVersion());
        assertEquals(DisputeCaseStatus.RESOLVED,c.status());assertThrows(AppException.class,()->accept(oldVersion));
        assertTrue(paymentRepo.findByContractIdAndType(id,PaymentType.DEPOSIT_REFUND).isEmpty());
        var fresh=proposal("50000");accept(fresh.getProposalVersion());
        assertEquals(0,paymentRepo.findByContractIdAndType(id,PaymentType.DEPOSIT_REFUND).orElseThrow().getTotalAmount().compareTo(new BigDecimal("19950000")));
    }
    @Test void failedCommentAuditNeverSharesAnUnattachedPrivateUpload(){
        var c=open(ComplaintCategory.CONDITION);String file=UUID.randomUUID().toString();
        tx(()->storage.saveAndFlush(StorageObject.builder().id(file).ownerId("tenant").originalName("private.pdf").objectKey("test/"+file).bucketName("test-only").sizeBytes(100L).contentType("application/pdf")
            .status(StorageStatus.READY).purpose(StoragePurpose.GENERAL).visibility(StorageVisibility.PRIVATE).referenceType("RENTAL_COMPLAINT_EVIDENCE").referenceId(rentalId).build()));
        doThrow(new IllegalStateException("Audit unavailable")).when(caseEvents).save(any(DisputeCaseEvent.class));
        assertThrows(IllegalStateException.class,()->complaints.act(c.id(),"tenant","COMMENT",new ComplaintAction(c.revision(),"Không được chia sẻ khi lưu phản hồi lỗi",List.of(file),null,null,null)));
        assertFalse(access.canAccess("owner",storage.findById(file).orElseThrow()));assertEquals(c.revision(),complaints.get(c.id(),"tenant").revision());
    }
    @Test void corruptedAuditFailsClosedForReadAndDeletion(){
        var c=open(ComplaintCategory.CONDITION);String file=UUID.randomUUID().toString();
        tx(()->storage.saveAndFlush(StorageObject.builder().id(file).ownerId("tenant").originalName("private.pdf").objectKey("test/"+file).bucketName("test-only").sizeBytes(100L).contentType("application/pdf")
            .status(StorageStatus.READY).purpose(StoragePurpose.GENERAL).visibility(StorageVisibility.PRIVATE).referenceType("RENTAL_COMPLAINT_EVIDENCE").referenceId(rentalId).build()));
        tx(()->em.createNativeQuery("update dispute_cases set initial_evidence_snapshot='{broken' where id=:id").setParameter("id",c.id()).executeUpdate());
        assertFalse(access.canAccess("owner",storage.findById(file).orElseThrow()));assertTrue(access.preventsDeletion(storage.findById(file).orElseThrow()));
    }
    @Test void adminCannotUseDifferentContextIdentityOrTenantAuthority(){
        var c=escalate(open(ComplaintCategory.REPAIR));admin("admin");
        assertThrows(AppException.class,()->complaints.adminAct(c.id(),"someone-else","CLAIM",action(c)));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin","n/a",List.of(new SimpleGrantedAuthority("TENANT"))));
        assertThrows(AppException.class,()->complaints.adminGet(c.id(),"admin"));
    }
    List<Integer> race(Runnable call){
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{Callable<Integer> worker=()->{assertTrue(start.await(10,TimeUnit.SECONDS));try{call.run();return 0;}catch(AppException e){return e.getCode();}finally{clear();}};
            var a=pool.submit(worker);var b=pool.submit(worker);start.countDown();var result=new ArrayList<>(List.of(a.get(25,TimeUnit.SECONDS),b.get(25,TimeUnit.SECONDS)));Collections.sort(result);return result;
        }catch(Exception e){throw new AssertionError(e);}finally{pool.shutdownNow();}
    }
}
