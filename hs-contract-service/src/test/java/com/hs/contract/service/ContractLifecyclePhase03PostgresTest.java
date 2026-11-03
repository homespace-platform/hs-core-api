package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.*;
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
@EnabledIfEnvironmentVariable(named="HS_LIFECYCLE_TEST_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_phase03_test")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_LIFECYCLE_TEST_PG_URL}","spring.datasource.username=postgres",
    "spring.datasource.password=hs_phase03_test","spring.jpa.hibernate.ddl-auto=create-drop","spring.profiles.active=test"})
@Import({ContractLifecycleService.class,MonthlyBillingService.class,PaymentRequestService.class,DepositLedgerService.class,PaymentDisputeService.class})
class ContractLifecyclePhase03PostgresTest {
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
    @Autowired ContractLifecycleService service;
    @Autowired MonthlyBillingService billing;
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

    @ParameterizedTest @CsvSource({"2026-12-04T10:00:00,2026-12-09T16:59:59Z","2026-12-10T10:00:00,2026-12-11T03:00:00Z"})
    void approvingOneDaySynchronizesInvoiceAndPaymentUsingTheLaterDeadline(String approvedAt,String expectedDue) {
        clock(approvedAt);var originalDue=Instant.parse("2026-12-08T16:59:59Z");var amount=new BigDecimal("3665000");
        String[] ids=new String[2];
        tx(()->{
            var bill=invoices.findByContractIdAndPeriodIndex(id,0).orElseThrow();
            bill.setPeriodStart(LocalDate.of(2026,11,4));bill.setPeriodEndExclusive(LocalDate.of(2026,12,4));
            bill.setStatus(MonthlyInvoiceStatus.UNPAID);bill.setDueAt(originalDue);bill.setOriginalDueAt(originalDue);
            bill.setBaseAmount(amount);bill.setTotalAmount(amount);bill.setLateFeeAmount(BigDecimal.ZERO);bill.setIssuedAt(Instant.parse("2026-12-04T03:00:00Z"));
            bill.setLineItemsSnapshot("[{\"type\":\"RENT\",\"description\":\"Tiền thuê và phí kỳ đầu\",\"quantity\":1,\"unitPrice\":3665000,\"amount\":3665000}]");
            var payment=paymentRepo.saveAndFlush(PaymentRequest.builder().rentalRequestId(rentalId).listingId(listingId).contractId(id).invoiceId(bill.getId())
                .payerId("tenant").payeeId("owner").type(PaymentType.MONTHLY_RENT).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.AWAITING_TRANSFER)
                .totalAmount(amount).dueAt(originalDue).currency("VND").qrImageUrl("existing-test-qr").transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());
            bill.setPaymentRequestId(payment.getId());invoices.saveAndFlush(bill);ids[0]=bill.getId();ids[1]=payment.getId();
        });
        var pending=billing.requestPaymentExtension(ids[0],"tenant",new PaymentExtensionPayload(1,"Xin gia hạn thanh toán thêm một ngày")).paymentExtensions().getFirst();
        assertEquals(originalDue,pending.baseDueAt());
        var approved=billing.decidePaymentExtension(ids[0],pending.id(),"owner",true);var due=Instant.parse(expectedDue);
        assertEquals(due,approved.dueAt());assertEquals(originalDue,approved.originalDueAt());
        assertEquals(PaymentExtension.Status.APPROVED,approved.paymentExtensions().getFirst().status());assertEquals(due,approved.paymentExtensions().getFirst().newDueAt());
        assertEquals(due,invoices.findById(ids[0]).orElseThrow().getDueAt());
        var stored=paymentRepo.findById(ids[1]).orElseThrow();assertEquals(due,stored.getDueAt());assertEquals(PaymentStatus.AWAITING_TRANSFER,stored.getStatus());
        assertEquals(0,amount.compareTo(stored.getTotalAmount()));assertEquals("existing-test-qr",stored.getQrImageUrl());
        assertThrows(AppException.class,()->billing.decidePaymentExtension(ids[0],pending.id(),"owner",true));
        assertEquals(due,paymentRepo.findById(ids[1]).orElseThrow().getDueAt());
    }

    @ParameterizedTest @CsvSource({"false","true"})
    void lateFeesResumeAfterFiveDayExtensionAndRemainConsistentWithTheRealPayment(boolean reportAndConfirm) {
        clock("2026-12-11T10:00:00");var originalDue=Instant.parse("2026-12-08T16:59:59Z");var base=new BigDecimal("3665000");
        String[] ids=new String[2];
        when(qr.generateQrImageUrl(anyString(),anyString(),anyString(),any(),anyString()))
            .thenAnswer(call->"test-qr-"+((BigDecimal)call.getArgument(3)).stripTrailingZeros().toPlainString());
        tx(()->{
            var contract=contracts.findById(id).orElseThrow();var revision=revisions.findById(contract.getCurrentRevisionId()).orElseThrow();
            revision.setPoliciesSnapshot("{\"latePaymentFeeMode\":\"FIXED_PER_DAY\",\"latePaymentFeeAmount\":100000,\"latePaymentFeeGraceDays\":0}");revisions.saveAndFlush(revision);
            var bill=invoices.findByContractIdAndPeriodIndex(id,0).orElseThrow();
            bill.setPeriodStart(LocalDate.of(2026,11,4));bill.setPeriodEndExclusive(LocalDate.of(2026,12,4));
            bill.setStatus(MonthlyInvoiceStatus.UNPAID);bill.setDueAt(originalDue);bill.setOriginalDueAt(originalDue);
            bill.setBaseAmount(base);bill.setTotalAmount(base);bill.setLateFeeAmount(BigDecimal.ZERO);bill.setIssuedAt(Instant.parse("2026-12-04T03:00:00Z"));
            bill.setLineItemsSnapshot("[{\"type\":\"RENT\",\"description\":\"Tiền thuê và phí kỳ đầu\",\"quantity\":1,\"unitPrice\":3665000,\"amount\":3665000}]");
            var payment=payments.createMonthlyPayment(bill.getId(),id,rentalId,listingId,"tenant","owner",base,bill.getLineItemsSnapshot(),originalDue);
            bill.setPaymentRequestId(payment.getId());invoices.saveAndFlush(bill);ids[0]=bill.getId();ids[1]=payment.getId();
        });
        var pending=billing.requestPaymentExtension(ids[0],"tenant",new PaymentExtensionPayload(5,"Xin gia hạn thêm năm ngày để thanh toán")).paymentExtensions().getFirst();
        assertEquals(0,new BigDecimal("300000").compareTo(pending.frozenLateFee()));
        clock("2026-12-12T10:00:00");var approved=billing.decidePaymentExtension(ids[0],pending.id(),"owner",true);
        assertEquals(Instant.parse("2026-12-17T03:00:00Z"),approved.dueAt());assertFalse(approved.lateFeeExempt());
        assertTrue(approved.paymentExtensions().getFirst().lateFeeResumesAfterDue());
        assertTrue(invoices.findById(ids[0]).orElseThrow().getExtensionRequestsSnapshot().contains("\"lateFeeResumesAfterDue\":true"));
        clock("2026-12-17T10:00:00");billing.syncContract(id);
        assertEquals(0,new BigDecimal("300000").compareTo(invoices.findById(ids[0]).orElseThrow().getLateFeeAmount()));
        clock("2026-12-18T00:01:00");billing.syncContract(id);billing.syncContract(id);
        var bill=invoices.findById(ids[0]).orElseThrow();var payment=paymentRepo.findById(ids[1]).orElseThrow();
        assertEquals(0,new BigDecimal("400000").compareTo(bill.getLateFeeAmount()));
        assertEquals(0,new BigDecimal("4065000").compareTo(bill.getTotalAmount()));assertEquals(0,bill.getTotalAmount().compareTo(payment.getTotalAmount()));
        assertEquals(bill.getLineItemsSnapshot(),payment.getBreakdownSnapshot());assertEquals(bill.getDueAt(),payment.getDueAt());
        assertEquals("test-qr-4065000",payment.getQrImageUrl());
        if(reportAndConfirm) {
            String evidence=proof(payment.getId(),"tenant");payments.reportTransfer(payment.getId(),"tenant",ReportTransferRequest.builder()
                .proofStorageId(evidence).expectedAmount(payment.getTotalAmount()).build());
            clock("2026-12-20T10:00:00");billing.syncContract(id);
            assertEquals(0,new BigDecimal("400000").compareTo(invoices.findById(ids[0]).orElseThrow().getLateFeeAmount()));
            payments.confirmReceipt(payment.getId(),"owner");billing.syncContract(id);
            assertEquals(MonthlyInvoiceStatus.PAID,invoices.findById(ids[0]).orElseThrow().getStatus());
            assertEquals(0,new BigDecimal("4065000").compareTo(paymentRepo.findById(ids[1]).orElseThrow().getTotalAmount()));
        }else {
            clock("2026-12-24T10:00:00");billing.syncContract(id);billing.syncContract(id);
            bill=invoices.findById(ids[0]).orElseThrow();payment=paymentRepo.findById(ids[1]).orElseThrow();
            assertEquals(0,new BigDecimal("500000").compareTo(bill.getLateFeeAmount()));
            assertEquals(0,new BigDecimal("4165000").compareTo(payment.getTotalAmount()));
            assertEquals(bill.getLineItemsSnapshot(),payment.getBreakdownSnapshot());assertEquals("test-qr-4165000",payment.getQrImageUrl());
        }
    }

    String[] overdueForUnilateral() {
        clock("2026-11-22T10:00:00");String[] ids=new String[2];
        tx(()->{
            for(var future:invoices.findByContractIdOrderByPeriodIndexDesc(id))if(future.getPeriodIndex()>0)invoices.delete(future);
            var contract=contracts.findById(id).orElseThrow();var revision=revisions.findById(contract.getCurrentRevisionId()).orElseThrow();
            revision.setPoliciesSnapshot("{\"latePaymentFeeMode\":\"FIXED_PER_DAY\",\"latePaymentFeeAmount\":100000,\"latePaymentFeeGraceDays\":0}");revisions.saveAndFlush(revision);
            var bill=invoices.findByContractIdAndPeriodIndex(id,0).orElseThrow();bill.setStatus(MonthlyInvoiceStatus.OVERDUE);
            bill.setIssuedAt(Instant.parse("2026-11-12T03:00:00Z"));bill.setDueAt(Instant.parse("2026-11-16T16:59:59Z"));bill.setOriginalDueAt(bill.getDueAt());
            bill.setBaseAmount(new BigDecimal("1000000"));bill.setTotalAmount(new BigDecimal("1000000"));bill.setLateFeeAmount(BigDecimal.ZERO);
            bill.setLineItemsSnapshot("[{\"type\":\"RENT\",\"description\":\"Tiền thuê\",\"quantity\":1,\"unitPrice\":1000000,\"amount\":1000000}]");
            var payment=payments.createMonthlyPayment(bill.getId(),id,rentalId,listingId,"tenant","owner",bill.getTotalAmount(),bill.getLineItemsSnapshot(),bill.getDueAt());
            bill.setPaymentRequestId(payment.getId());invoices.saveAndFlush(bill);ids[0]=bill.getId();ids[1]=payment.getId();
        });return ids;
    }
    UnilateralTerminationNotice storedNotice(String invoiceId) {
        return billing.list(id,"owner").stream().filter(b->b.id().equals(invoiceId)).findFirst().orElseThrow().unilateralTermination();
    }
    @ParameterizedTest @CsvSource({"NO_REVIEW","REJECTED","ROLLOVER","PAYMENT_EXTENSION","CONFIRMED"})
    void unilateralDecisionPersistsAcross24HoursAndOnlyTheSelectedOutcomeIsApplied(String outcome) {
        var ids=overdueForUnilateral();var listingBefore=properties.findById(listingId).orElseThrow().getStatus();
        var n=billing.issueUnilateralTermination(ids[0],"owner",new UnilateralTerminationPayloads.Issue("Quá hạn thanh toán, thông báo đơn phương chấm dứt")).unilateralTermination();
        assertEquals(Instant.parse("2026-11-23T03:00:00Z"),n.respondBy());assertNull(contracts.findById(id).orElseThrow().getTerminationAcceptedAt());
        assertEquals(0,new BigDecimal("500000").compareTo(invoices.findById(ids[0]).orElseThrow().getLateFeeAmount()));
        assertTrue(contracts.findById(id).orElseThrow().getUnilateralTerminationSnapshot().contains(n.id()));
        tx(()->invoices.saveAndFlush(MonthlyInvoice.builder().contractId(id).periodIndex(1)
            .periodStart(LocalDate.of(2026,11,12)).periodEndExclusive(LocalDate.of(2026,12,12))
            .status(MonthlyInvoiceStatus.DRAFT).totalAmount(BigDecimal.ZERO).build()));
        if("CONFIRMED".equals(outcome)) {
            String evidence=proof(ids[1],"tenant");payments.reportTransfer(ids[1],"tenant",ReportTransferRequest.builder().proofStorageId(evidence).expectedAmount(new BigDecimal("1500000")).build());
            clock("2026-11-23T11:00:00");billing.syncContract(id);assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());
            payments.confirmReceipt(ids[1],"owner");billing.syncContract(id);
            assertEquals(UnilateralTerminationNotice.Status.CANCELLED_PAID,storedNotice(ids[0]).status());
            assertEquals(MonthlyInvoiceStatus.PAID,invoices.findById(ids[0]).orElseThrow().getStatus());return;
        }
        if(!"NO_REVIEW".equals(outcome)) {
            var option="ROLLOVER".equals(outcome)?UnilateralTerminationNotice.Option.ROLLOVER:UnilateralTerminationNotice.Option.PAYMENT_EXTENSION;
            n=billing.requestTerminationReview(ids[0],n.id(),"tenant",new UnilateralTerminationPayloads.Review(n.version(),option,
                option==UnilateralTerminationNotice.Option.PAYMENT_EXTENSION?5:null,"Xin chủ nhà chấp nhận phương án thanh toán mới")).unilateralTermination();
            clock("2026-11-24T10:00:00");billing.syncContract(id);assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());
            n=billing.decideTerminationReview(ids[0],n.id(),"owner",new UnilateralTerminationPayloads.Decision(n.version(),!"REJECTED".equals(outcome),"Chủ nhà đã xem xét và quyết định phương án")).unilateralTermination();
        }else {clock("2026-11-23T10:00:00");billing.syncContract(id);n=storedNotice(ids[0]);}
        if("ROLLOVER".equals(outcome)||"PAYMENT_EXTENSION".equals(outcome)) {
            assertEquals(ContractStatus.ACTIVE,contracts.findById(id).orElseThrow().getStatus());assertEquals(UnilateralTerminationNotice.Status.REPLACED,n.status());
            var bill=invoices.findById(ids[0]).orElseThrow();
            if("ROLLOVER".equals(outcome)){assertNotNull(bill.getDeferredAt());assertEquals("ROLLOVER",bill.getResolutionMode());}
            else {assertNull(bill.getDeferredAt());assertEquals(Instant.parse("2026-11-29T03:00:00Z"),bill.getDueAt());assertEquals(bill.getDueAt(),paymentRepo.findById(ids[1]).orElseThrow().getDueAt());}
        }else {
            assertEquals(UnilateralTerminationNotice.Status.ENDED,n.status());assertEquals(ContractStatus.TERMINATED,contracts.findById(id).orElseThrow().getStatus());
            assertNull(contracts.findById(id).orElseThrow().getTerminationAcceptedAt());assertNull(n.handoverAt());
            var cancelled=billing.list(id,"tenant").stream().filter(b->b.periodIndex()==1).findFirst().orElseThrow();
            assertEquals(MonthlyInvoiceStatus.CANCELLED,cancelled.status());assertEquals("CANCELLED",cancelled.workflowState());assertNull(cancelled.paymentRequestId());
            assertEquals(listingBefore,properties.findById(listingId).orElseThrow().getStatus());assertEquals(DepositStatus.HELD,deposits.findByRentalRequestId(rentalId).orElseThrow().getStatus());
            var endedAt=n.endedAt();clock("2026-11-25T10:00:00");
            var done=billing.completeUnilateralHandover(ids[0],n.id(),"owner",new UnilateralTerminationPayloads.Handover(n.version(),true,true));
            assertNotNull(done.unilateralTermination().handoverAt());assertEquals(endedAt,contracts.findById(id).orElseThrow().getTerminationCompletedAt());
            assertEquals(0,principal.compareTo(deposits.findByRentalRequestId(rentalId).orElseThrow().getRetainedAmount()));
            verify(listings,times(1)).releaseRentedAfterTermination(listingId,"owner");verify(parking,times(1)).releaseReservationsForContract(id);
            var stale=n;assertThrows(AppException.class,()->billing.completeUnilateralHandover(ids[0],stale.id(),"owner",new UnilateralTerminationPayloads.Handover(stale.version(),true,true)));
        }
    }
    @Test void opposingOwnerDecisionsCannotBothCommitAgainstTheSameReviewVersion() {
        var ids=overdueForUnilateral();var notice=billing.issueUnilateralTermination(ids[0],"owner",new UnilateralTerminationPayloads.Issue("Thông báo đơn phương để kiểm thử đồng thời")).unilateralTermination();
        var n=billing.requestTerminationReview(ids[0],notice.id(),"tenant",new UnilateralTerminationPayloads.Review(1,UnilateralTerminationNotice.Option.PAYMENT_EXTENSION,5,"Xin dời hạn để chờ tiền lương về")).unilateralTermination();
        var turn=new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(List.of(0,1),race(()->billing.decideTerminationReview(ids[0],n.id(),"owner",new UnilateralTerminationPayloads.Decision(n.version(),turn.getAndIncrement()==0,"Một quyết định được phép ghi nhận duy nhất"))));
        var contract=contracts.findById(id).orElseThrow();var stored=storedNotice(ids[0]);assertEquals(3,stored.version());assertNull(contract.getTerminationAcceptedAt());
        if(stored.status()==UnilateralTerminationNotice.Status.ENDED){assertEquals(ContractStatus.TERMINATED,contract.getStatus());assertNull(invoices.findById(ids[0]).orElseThrow().getExtensionRequestsSnapshot());}
        else {assertEquals(UnilateralTerminationNotice.Status.REPLACED,stored.status());assertEquals(ContractStatus.ACTIVE,contract.getStatus());assertEquals(invoices.findById(ids[0]).orElseThrow().getDueAt(),paymentRepo.findById(ids[1]).orElseThrow().getDueAt());}
    }

    @ParameterizedTest @CsvSource({"0,0,20000000,0","100000,100000,19900000,0","20000000,20000000,0,0","21000000,20000000,0,1000000"})
    void settlementBalancesActualDepositAndKeepsExcessDebtSeparate(String deduction,String retained,String refund,String balance){
        var close=proposal(deduction);accept(close.getProposalVersion());
        var d=deposits.findByRentalRequestId(rentalId).orElseThrow();
        assertEquals(0,d.getRetainedAmount().compareTo(new BigDecimal(retained)));assertEquals(0,d.getHeldAmount().compareTo(new BigDecimal(refund)));
        assertEquals(0,ledger.forRental(rentalId).confirmedRefundedAmount().signum());
        assertEquals(0,principal.compareTo(d.getRetainedAmount().add(d.getHeldAmount())));
        if(new BigDecimal(retained).signum()>0){assertEquals(time.now(),d.getRetainedAt());assertEquals(close.getNote(),d.getRetainedReason());}
        else assertNull(d.getRetainedAt());
        var actual=closures.findById(id).orElseThrow();assertEquals(0,actual.getBalanceAmount().compareTo(new BigDecimal(balance)));
        assertThrows(AppException.class,()->accept(close.getProposalVersion()));
        assertEquals(0,deposits.findByRentalRequestId(rentalId).orElseThrow().getRetainedAmount().compareTo(new BigDecimal(retained)));
        assertEquals(new BigDecimal(balance).signum()>0?"BALANCE_PENDING":new BigDecimal(refund).signum()>0?"REFUND_PENDING":"CLOSED",actual.getStatus());
    }
    private void waiveDeposit() {
        tx(()->{
            var r=rentals.findById(rentalId).orElseThrow();r.setDepositAmount(BigDecimal.ZERO);rentals.saveAndFlush(r);
            var d=deposits.findByRentalRequestId(rentalId).orElseThrow();d.setOriginalAmount(BigDecimal.ZERO);d.setHeldAmount(BigDecimal.ZERO);d.setRefundableAmount(BigDecimal.ZERO);deposits.saveAndFlush(d);
            var p=paymentRepo.findById(d.getInitialPaymentRequestId()).orElseThrow();p.setTotalAmount(BigDecimal.ZERO);paymentRepo.saveAndFlush(p);
        });
    }
    @ParameterizedTest @CsvSource({"0,CLOSED","100000,BALANCE_PENDING"})
    void noDepositClosureSkipsRefundButStillCollectsAnAgreedBalance(String charge,String expectedStatus) {
        waiveDeposit();var close=proposal(charge);accept(close.getProposalVersion());
        var actual=closures.findById(id).orElseThrow();var d=deposits.findByRentalRequestId(rentalId).orElseThrow();
        assertEquals(expectedStatus,actual.getStatus());assertNull(actual.getRefundPaymentId());
        assertTrue(paymentRepo.findAllByContractIdAndType(id,PaymentType.DEPOSIT_REFUND).isEmpty());
        assertEquals(DepositStatus.HELD,d.getStatus());assertEquals(0,d.getRetainedAmount().signum());assertNull(d.getRetainedAt());
        if("0".equals(charge)) {
            assertNull(actual.getRefundDueAt());assertNull(actual.getBalancePaymentId());assertEquals(ContractStatus.CLOSED,contracts.findById(id).orElseThrow().getStatus());
        }else {
            assertEquals(ContractStatus.EXPIRED,contracts.findById(id).orElseThrow().getStatus());
            var payment=paymentRepo.findById(actual.getBalancePaymentId()).orElseThrow();assertEquals(0,new BigDecimal(charge).compareTo(payment.getTotalAmount()));
            assertEquals("tenant",payment.getPayerId());assertEquals("owner",payment.getPayeeId());
            String evidence=proof(payment.getId(),"tenant");payments.reportTransfer(payment.getId(),"tenant",ReportTransferRequest.builder().proofStorageId(evidence).expectedAmount(new BigDecimal(charge)).build());
            assertEquals("BALANCE_PENDING",service.get(id,"owner").closure().getStatus());
            payments.confirmReceipt(payment.getId(),"owner");assertEquals("CLOSED",service.get(id,"tenant").closure().getStatus());
        }
        assertThrows(AppException.class,()->accept(close.getProposalVersion()));
    }
    @Test void waivedDepositDoesNotReleaseThePropertyWithAnUnpaidFinalBillOrWithoutHandover() {
        waiveDeposit();clock("2027-01-14T10:00:00");
        tx(()->{var bill=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();bill.setStatus(MonthlyInvoiceStatus.DRAFT);invoices.saveAndFlush(bill);});
        assertFalse(service.get(id,"owner").canProposeClosure());assertThrows(AppException.class,()->proposal("0"));
        tx(()->{var bill=invoices.findByContractIdAndPeriodIndex(id,2).orElseThrow();bill.setStatus(MonthlyInvoiceStatus.PAID);invoices.saveAndFlush(bill);});
        assertThrows(AppException.class,()->service.proposeClosure(id,"owner",new ClosurePayload(null,null,BigDecimal.ZERO,"Chưa nhận lại nhà và chìa khóa",false)));
        assertEquals(ContractStatus.EXPIRED,contracts.findById(id).orElseThrow().getStatus());
        verify(listings,never()).releaseRentedAfterTermination(anyString(),anyString());verify(parking,never()).releaseReservationsForContract(anyString());
    }
    @Test void initiallyZeroDepositStillRefundsAnActuallyReceivedDepositAdjustment() {
        waiveDeposit();tx(()->{
            var d=deposits.findByRentalRequestId(rentalId).orElseThrow();d.setHeldAmount(new BigDecimal("3000000"));d.setRefundableAmount(new BigDecimal("3000000"));deposits.saveAndFlush(d);
            paymentRepo.saveAndFlush(PaymentRequest.builder().rentalRequestId(rentalId).listingId(listingId).contractId(id).payerId("tenant").payeeId("owner")
                .type(PaymentType.DEPOSIT_ADJUSTMENT).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("3000000"))
                .confirmedAt(time.now()).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());
        });
        var close=proposal("0");accept(close.getProposalVersion());
        var result=service.get(id,"tenant");assertEquals("REFUND_PENDING",result.closure().getStatus());assertEquals(0,new BigDecimal("3000000").compareTo(result.refundPayment().totalAmount()));
        assertEquals(0,new BigDecimal("3000000").compareTo(ledger.forRental(rentalId).confirmedReceivedAmount()));
    }
    @Test void refundRejectedAndDisputedThenConfirmedKeepsRetentionAndClosesOnlyAfterRealReceipt(){
        var close=proposal("100000");accept(close.getProposalVersion());String paymentId=closures.findById(id).orElseThrow().getRefundPaymentId();
        String proof=proof(paymentId,"owner");var amount=new BigDecimal("19900000");
        payments.reportTransfer(paymentId,"owner",ReportTransferRequest.builder().proofStorageId(proof).expectedAmount(amount).build());
        payments.rejectReceipt(paymentId,"tenant",new RejectReceiptRequest("Chưa thấy tiền hoàn trong tài khoản"));
        payments.dispute(paymentId,"owner",new DisputePaymentRequest("Đã chuyển khoản cần kiểm tra ngân hàng"));
        var dispute=disputes.forPayment(paymentId,"owner").getFirst();
        disputes.act(dispute.id(),"owner","PROPOSE",new DisputeCaseAction(dispute.revision(),"Đối chiếu lại chứng từ ngân hàng",DisputeResolution.REVIEW_TRANSFER,null));
        dispute=disputes.forPayment(paymentId,"tenant").getFirst();
        disputes.act(dispute.id(),"tenant","ACCEPT",new DisputeCaseAction(dispute.revision(),"Đồng ý kiểm tra lại khoản đã chuyển",null,null));
        assertEquals("REFUND_PENDING",service.get(id,"tenant").closure().getStatus());
        assertEquals(0,ledger.forRental(rentalId).confirmedRefundedAmount().signum());
        payments.confirmReceipt(paymentId,"tenant");payments.confirmReceipt(paymentId,"tenant");
        assertEquals("CLOSED",service.get(id,"tenant").closure().getStatus());
        var d=ledger.forRental(rentalId);assertEquals(0,d.retainedAmount().compareTo(new BigDecimal("100000")));
        assertEquals(0,d.confirmedRefundedAmount().compareTo(amount));assertEquals(0,d.heldAmount().signum());
        assertEquals(0,d.confirmedReceivedAmount().compareTo(d.retainedAmount().add(d.confirmedRefundedAmount())));
    }
    @Test void failureAfterCreatingRefundRollsBackLedgerAcceptanceAndObligationThenRetrySucceeds(){
        var close=proposal("100000");
        doThrow(new IllegalStateException("Test parking failure")).doNothing().when(parking).releaseReservationsForContract(id);
        assertThrows(IllegalStateException.class,()->accept(close.getProposalVersion()));
        var d=deposits.findByRentalRequestId(rentalId).orElseThrow();assertNull(d.getRetainedAmount());assertEquals(0,d.getHeldAmount().compareTo(principal));
        assertEquals("PROPOSED",closures.findById(id).orElseThrow().getStatus());assertNull(closures.findById(id).orElseThrow().getAcceptedAt());
        assertTrue(paymentRepo.findByContractIdAndType(id,PaymentType.DEPOSIT_REFUND).isEmpty());
        accept(close.getProposalVersion());assertEquals(0,deposits.findByRentalRequestId(rentalId).orElseThrow().getRetainedAmount().compareTo(new BigDecimal("100000")));
    }
    @Test void changedHeldBalanceInvalidatesOldProposalInsteadOfLosingMoney(){
        var close=proposal("100000");tx(()->{var d=deposits.findByRentalRequestIdForUpdate(rentalId).orElseThrow();d.setHeldAmount(new BigDecimal("21000000"));deposits.saveAndFlush(d);});
        assertThrows(AppException.class,()->accept(close.getProposalVersion()));assertNull(closures.findById(id).orElseThrow().getAcceptedAt());
    }
    @Test void confirmedTopUpAndPartialRefundRemainBalancedThroughFinalSettlement(){
        for(String amount:List.of("3000000","-2000000")) {
            var p=payments.createDepositAdjustment(UUID.randomUUID().toString(),id,rentalId,listingId,"owner","tenant",new BigDecimal(amount),time.now().plusSeconds(3600));
            String proof=proof(p.getId(),p.getPayerId());
            payments.reportTransfer(p.getId(),p.getPayerId(),ReportTransferRequest.builder().proofStorageId(proof).expectedAmount(p.getTotalAmount()).build());
            payments.confirmReceipt(p.getId(),p.getPayeeId());
        }
        assertEquals(0,ledger.forRental(rentalId).heldAmount().compareTo(new BigDecimal("21000000")));
        var c=proposal("100000");accept(c.getProposalVersion());
        var p=paymentRepo.findByContractIdAndType(id,PaymentType.DEPOSIT_REFUND).orElseThrow();String proof=proof(p.getId(),"owner");
        payments.reportTransfer(p.getId(),"owner",ReportTransferRequest.builder().proofStorageId(proof).expectedAmount(new BigDecimal("20900000")).build());
        payments.confirmReceipt(p.getId(),"tenant");
        var d=service.get(id,"tenant").deposit();
        assertEquals(0,d.confirmedReceivedAmount().compareTo(new BigDecimal("23000000")));
        assertEquals(0,d.confirmedRefundedAmount().compareTo(new BigDecimal("22900000")));
        assertEquals(0,d.confirmedReceivedAmount().compareTo(d.retainedAmount().add(d.confirmedRefundedAmount()).add(d.heldAmount())));
    }
    @Test void fullAcceptedReasonIsPreservedWhenLongerThanOldLedgerColumn(){
        clock("2027-01-14T10:00:00");String note="Nội dung quyết toán "+"x".repeat(1900);
        var c=service.proposeClosure(id,"owner",new ClosurePayload(null,null,new BigDecimal("100000"),note,true)).closure();
        accept(c.getProposalVersion());assertEquals(note,ledger.forRental(rentalId).retainedReason());
    }
    @Test void alreadyReadClosureCannotBeAcceptedAgainAfterConcurrentCommit(){
        var c=proposal("100000");
        assertThrows(AppException.class,()->tx(()->{
            closures.findById(id).orElseThrow();
            try {CompletableFuture.runAsync(()->accept(c.getProposalVersion())).get(15,TimeUnit.SECONDS);}
            catch(Exception e){throw new AssertionError(e);}
            accept(c.getProposalVersion());
        }));
        assertEquals(1,paymentRepo.findAllByContractIdAndType(id,PaymentType.DEPOSIT_REFUND).size());
        assertEquals(0,ledger.forRental(rentalId).retainedAmount().compareTo(new BigDecimal("100000")));
    }
    @Test void simultaneousAcceptanceCreatesExactlyOneRefundAndOneRetention(){
        var close=proposal("100000");assertEquals(List.of(0,1),race(()->accept(close.getProposalVersion())));
        assertEquals(1,paymentRepo.findAllByContractIdAndType(id,PaymentType.DEPOSIT_REFUND).size());
        assertEquals(0,deposits.findByRentalRequestId(rentalId).orElseThrow().getRetainedAmount().compareTo(new BigDecimal("100000")));
    }
    @Test void rejectedThreeMonthProposalAllowsFreshOneMonthProposalWithoutChangingHistory(){
        var old=request(3);service.decideRenewal(id,old.getId(),"owner",false);
        assertTrue(service.get(id,"tenant").canRequestRenewal());var fresh=request(1);
        assertNotEquals(old.getId(),fresh.getId());assertEquals(3,renewals.findById(old.getId()).orElseThrow().getMonths());
        assertEquals("REJECTED",renewals.findById(old.getId()).orElseThrow().getStatus());
        assertEquals(LocalDate.of(2027,2,12),fresh.getEndExclusive());assertFalse(service.get(id,"tenant").canRequestRenewal());
        assertNull(contracts.findById(id).orElseThrow().getEffectiveLeaseMonths());verifyNoInteractions(parking);
        assertEquals(1,paymentRepo.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.RESERVATION_DEPOSIT,PaymentType.INITIAL)).size());
    }
    @Test void responseExpiryAllowsRetryBeforeCutoffButNeverAtOrAfterSigningCutoff(){
        var old=request(3);clock("2026-12-15T10:00:00");assertTrue(service.get(id,"tenant").canRequestRenewal());
        assertEquals("EXPIRED",renewals.findById(old.getId()).orElseThrow().getStatus());request(1);
        clock("2027-01-11T00:00:00");assertFalse(service.get(id,"tenant").canRequestRenewal());assertThrows(AppException.class,()->request(1));
    }
    @Test void simultaneousRequestsAndApprovalsOnlyCreateOnePendingProposalAndAgreement(){
        assertEquals(List.of(0,1),race(()->request(1)));var r=renewals.findByContractIdOrderByRequestedAtDesc(id).getFirst();
        assertEquals(List.of(0,1),race(()->approve(r)));
        assertEquals(1,renewals.findByContractIdOrderByRequestedAtDesc(id).size());
        verify(parking,times(1)).holdForRenewal(eq(id),anyString(),any(),any(),any());
    }
    @Test void repeatedVerifiedCallbackOnlyExtendsOnceAndDoesNotChargeAgain(){
        var child=approve(request(1));signed(child);assertEquals(List.of(0,0),race(()->service.applyRenewalAfterSignatures(child)));
        assertEquals(4,contracts.findById(id).orElseThrow().getEffectiveLeaseMonths());
        verify(parking,times(1)).activateRenewalHold(child.getId(),id);
        assertEquals(0,deposits.findByRentalRequestId(rentalId).orElseThrow().getHeldAmount().compareTo(principal));
        assertEquals(1,paymentRepo.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.RESERVATION_DEPOSIT,PaymentType.INITIAL)).size());
    }
    @Test void lateCallbackForOldExpiredRequestCannotApplyToReplacement(){
        var old=request(3);clock("2026-12-15T10:00:00");var child=approve(request(1));
        var obsolete=Contract.builder().id(UUID.randomUUID().toString()).parentContractId(id).currentRevisionId("old-revision").build();
        tx(()->{var r=renewals.findById(old.getId()).orElseThrow();r.setAgreementContractId(obsolete.getId());renewals.saveAndFlush(r);});
        assertThrows(AppException.class,()->service.applyRenewalAfterSignatures(obsolete));
        assertNull(contracts.findById(id).orElseThrow().getEffectiveLeaseMonths());
        assertEquals("APPROVED",renewals.findByAgreementContractId(child.getId()).orElseThrow().getStatus());
        verify(parking,never()).activateRenewalHold(anyString(),anyString());
    }
    @Test void activeTerminationOrPendingAmendmentBlocksRetryDespiteRejectedHistory(){
        var old=request(3);service.decideRenewal(id,old.getId(),"owner",false);
        when(amendments.hasPending(any())).thenReturn(true);assertThrows(AppException.class,()->request(1));
        when(amendments.hasPending(any())).thenReturn(false);
        tx(()->{var c=contracts.findByIdForUpdate(id).orElseThrow();c.setTerminationProposedAt(time.now());contracts.saveAndFlush(c);});
        assertThrows(AppException.class,()->request(1));
    }
    List<Integer> race(Runnable call){
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> run=()->{assertTrue(start.await(10,TimeUnit.SECONDS));try{call.run();return 0;}catch(AppException e){return 1;}};
            var a=pool.submit(run);var b=pool.submit(run);start.countDown();
            var results=new ArrayList<>(List.of(a.get(25,TimeUnit.SECONDS),b.get(25,TimeUnit.SECONDS)));Collections.sort(results);return results;
        } catch(Exception e){throw new AssertionError(e);}finally{pool.shutdownNow();}
    }
}
