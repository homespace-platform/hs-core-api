package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.RentalWorkflowPayloads;
import com.hs.contract.model.Contract;
import com.hs.contract.model.RentalWorkflowRecord;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.notification.TerminationNotificationWriter;
import com.hs.contract.repository.*;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.repository.PaymentLineItemRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentRequestService;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MutualTerminationServiceTest {
    final RentalWorkflowRecordRepository records = mock(RentalWorkflowRecordRepository.class);
    final ContractRepository contracts = mock(ContractRepository.class);
    final ContractRenewalRepository renewals = mock(ContractRenewalRepository.class);
    final ContractAmendmentRepository amendments = mock(ContractAmendmentRepository.class);
    final MonthlyInvoiceRepository invoices = mock(MonthlyInvoiceRepository.class);
    final PaymentLineItemRepository lineRecords = mock(PaymentLineItemRepository.class);
    final FinalRentCalculator calculator = mock(FinalRentCalculator.class);
    final ContractLifecycleService lifecycle = mock(ContractLifecycleService.class);
    final PaymentRequestRepository paymentRecords = mock(PaymentRequestRepository.class);
    final PaymentRequestService payments = mock(PaymentRequestService.class);
    final TerminationNotificationWriter notices = mock(TerminationNotificationWriter.class);
    final BillingTime time = mock(BillingTime.class);
    final Contract contract = Contract.builder().id("lease").rentalRequestId("rental")
        .landlordId("owner").tenantId("tenant").currentRevisionId("revision")
        .status(ContractStatus.EXPIRED).signedAt(Instant.parse("2026-10-01T00:00:00Z"))
        .mutualTerminationWorkflowId("rental:EARLY_EXIT").build();
    final RentalRequest rental = RentalRequest.builder().id("rental").ownerId("owner")
        .renterId("tenant").moveInDate(LocalDate.of(2026,10,1)).leaseMonths(1).build();
    final RentalWorkflowRecord workflow = new RentalWorkflowRecord();
    RentalWorkflowSupport support;
    MutualTerminationService service;

    @BeforeEach void setup() {
        when(time.now()).thenReturn(Instant.parse("2026-11-02T03:00:00Z"));
        when(time.today()).thenReturn(LocalDate.of(2026,11,2));
        workflow.setId("rental:EARLY_EXIT"); workflow.setRentalRequestId("rental");
        workflow.setKind("EARLY_EXIT"); workflow.setStatus("PROPOSED"); workflow.setRevision(4);
        workflow.setProposedBy("owner"); workflow.setSourceRevisionId("revision");
        support = spy(new RentalWorkflowSupport(records, mock(RentalRequestRepository.class), contracts,
            mock(ContractRevisionRepository.class), mock(StorageObjectRepository.class), paymentRecords,
            new ObjectMapper().findAndRegisterModules(), time));
        for (String actor : new String[]{"owner", "tenant"}) {
            doReturn(new RentalWorkflowSupport.Source(rental,contract,workflow,actor))
                .when(support).lock("rental",actor,"EARLY_EXIT");
        }
        service = new MutualTerminationService(support, renewals, amendments, invoices,
            lineRecords, payments, mock(ComplaintCaseService.class));
        var effective=mock(ContractTermsResolver.class);
        when(effective.at(any(),any())).thenAnswer(call->com.hs.contract.model.ContractRevision.builder().id(contract.getCurrentRevisionId()).build());
        ReflectionTestUtils.setField(service,"effectiveTerms",effective);
        ReflectionTestUtils.setField(service,"signingDocuments",mock(WorkflowSigningDocumentRepository.class));
        ReflectionTestUtils.setField(service,"workflowDocuments",mock(WorkflowDocumentService.class));
        ReflectionTestUtils.setField(service,"terminationNotices",notices);
        ReflectionTestUtils.setField(service,"rentCalculator",calculator);
        ReflectionTestUtils.setField(service,"lifecycle",lifecycle);
        when(lifecycle.get(anyString(),anyString())).thenReturn(mock(com.hs.contract.dto.billing.ContractLifecycleResponse.class));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"owner","tenant"})
    void eitherPartyCanCancelAnApprovedUnsignedAnnex(String actor){
        workflow.setStatus("APPROVED");
        assertTrue(service.get("rental",actor).allowedActions().contains("CANCEL"));
        var cancelled=service.act("rental",actor,"CANCEL",new RentalWorkflowPayloads.Action(4L,"Hủy phụ lục đang chờ hai bên ký số",false,false));
        assertEquals("WITHDRAWN",cancelled.status());
        assertNull(contract.getMutualTerminationWorkflowId());
    }

    @ParameterizedTest @CsvSource({"owner,WITHDRAW", "tenant,REJECT"})
    void expiredLeaseStillOffersTheCorrectWayToDismissThePendingProposal(String actor,String action) {
        var view = service.get("rental",actor);
        assertTrue(view.allowedActions().contains(action));
        assertFalse(view.allowedActions().contains("ACCEPT"));
        assertFalse(view.allowedActions().contains("PROPOSE"));
        assertNotNull(view.blockedReason());
        assertEquals(4,workflow.getRevision());
        verifyNoInteractions(payments,records,notices);
    }

    @ParameterizedTest @CsvSource({"owner,WITHDRAW,WITHDRAWN", "tenant,REJECT,REJECTED"})
    void dismissingAnExpiredProposalKeepsTheLeaseAndMoneyUnchanged(String actor,String action,String status) {
        var result = service.act("rental",actor,action,new RentalWorkflowPayloads.Action(4L,"Không tiếp tục đề nghị chấm dứt cũ",false,false));
        assertEquals(status,result.status()); assertEquals(5,result.revision());
        assertNull(contract.getMutualTerminationWorkflowId());
        assertNull(contract.getAgreedEarlyEndExclusive());
        assertEquals(ContractStatus.EXPIRED,contract.getStatus());
        verifyNoInteractions(payments,invoices);
        verify(records).saveAndFlush(workflow);
    }

    @ParameterizedTest @CsvSource({"owner,WITHDRAW", "tenant,REJECT"})
    void staleDismissalCannotChangeTheProposalOrMoney(String actor,String action) {
        assertThrows(AppException.class,()->service.act("rental",actor,action,
            new RentalWorkflowPayloads.Action(3L,"Không tiếp tục đề nghị chấm dứt cũ",false,false)));
        assertEquals("PROPOSED",workflow.getStatus()); assertEquals(4,workflow.getRevision());
        verifyNoInteractions(records,payments,notices);
    }

    @ParameterizedTest @CsvSource({"owner,REJECT", "tenant,WITHDRAW"})
    void dismissingTheProposalStillRequiresTheCorrectParty(String actor,String action) {
        assertThrows(AppException.class,()->service.act("rental",actor,action,
            new RentalWorkflowPayloads.Action(4L,"Không tiếp tục đề nghị chấm dứt cũ",false,false)));
        assertEquals("PROPOSED",workflow.getStatus()); assertEquals(4,workflow.getRevision());
        verifyNoInteractions(records,payments,notices);
    }

    @Test void expiredLeaseCannotBeAgreedEvenWithAcknowledgement() {
        assertThrows(AppException.class,()->service.act("rental","tenant","ACCEPT",
            new RentalWorkflowPayloads.Action(4L,"Đồng ý ngày kết thúc trong đề nghị",true,false)));
        assertNull(contract.getAgreedEarlyEndExclusive());
        assertEquals("PROPOSED",workflow.getStatus());
        verifyNoInteractions(records,payments,notices);
    }

    void active(){
        contract.setStatus(ContractStatus.ACTIVE);rental.setMoveInDate(LocalDate.of(2027,1,31));rental.setLeaseMonths(4);
        when(time.today()).thenReturn(LocalDate.of(2027,2,1));when(time.now()).thenReturn(Instant.parse("2027-02-01T03:00:00Z"));
        workflow.setStatus("EMPTY");workflow.setRevision(0);workflow.setProposedBy(null);workflow.setPayloadJson(null);contract.setMutualTerminationWorkflowId(null);
    }
    RentalWorkflowPayloads.Termination request(long revision,LocalDate end){return new RentalWorkflowPayloads.Termination(revision,end,"Đề nghị kết thúc đúng kỳ thuê này",null,null,null,null);}
    RentalWorkflowPayloads.Action consent(long revision){return new RentalWorkflowPayloads.Action(revision,"Tôi đã đọc và đồng ý đúng ngày đề nghị",true,false);}
    @Test void choicesAnchorEveryBoundaryOnOriginalMoveInDayWithoutFebruaryDrift(){
        active();var view=service.get("rental","tenant");var choices=view.terminationOptions().choices();
        assertEquals(java.util.List.of(LocalDate.of(2027,2,27),LocalDate.of(2027,3,30),LocalDate.of(2027,4,29)),choices.stream().map(com.hs.contract.dto.billing.RentalWorkflowView.Choice::lastOccupiedDay).toList());
        assertEquals(LocalDate.of(2027,5,31),view.terminationOptions().currentEndExclusive());
        assertTrue(view.allowedActions().contains("PROPOSE"));assertFalse(service.get("rental","owner").allowedActions().contains("PROPOSE"));
        verify(contracts,never()).save(any());verifyNoInteractions(payments,records,notices);
    }
    @Test void leapYearBoundariesUseTheSameOriginalAnchor(){
        active();rental.setMoveInDate(LocalDate.of(2028,1,31));when(time.today()).thenReturn(LocalDate.of(2028,2,1));
        var choices=service.get("rental","tenant").terminationOptions().choices();
        assertEquals(LocalDate.of(2028,2,28),choices.getFirst().lastOccupiedDay());assertEquals(LocalDate.of(2028,3,30),choices.get(1).lastOccupiedDay());
    }
    @Test void lastOccupiedDayTodayIsAllowedButYesterdayIsNot(){
        active();when(time.today()).thenReturn(LocalDate.of(2027,2,27));
        assertEquals(LocalDate.of(2027,2,27),service.get("rental","tenant").terminationOptions().choices().getFirst().lastOccupiedDay());
        when(time.today()).thenReturn(LocalDate.of(2027,2,28));
        var choices=service.get("rental","tenant").terminationOptions().choices();assertEquals(LocalDate.of(2027,3,30),choices.getFirst().lastOccupiedDay());
        assertThrows(AppException.class,()->service.propose("rental","tenant",request(0,LocalDate.of(2027,2,28))));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"2027-01-31","2027-02-15","2027-05-31","2027-06-30"})
    void invalidOrOriginalContractEndCannotBeProposed(String date){
        active();assertThrows(AppException.class,()->service.propose("rental","tenant",request(0,LocalDate.parse(date))));
        assertNull(contract.getAgreedEarlyEndExclusive());verify(contracts,never()).save(any());verifyNoInteractions(records,payments,notices);
    }
    @Test void tenantProposalNormalizesTechnicalFieldsWithoutChangingEffectiveEnd(){
        active();var result=service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31)));
        assertEquals("PROPOSED",result.status());assertNull(contract.getAgreedEarlyEndExclusive());assertNull(contract.getAgreedEarlyBillingMonths());
        assertEquals("FULL_STARTED_PERIOD",((java.util.Map<?,?>)result.proposal()).get("finalRentPolicy"));
        assertEquals(java.util.List.of(),((java.util.Map<?,?>)result.proposal()).get("rentRefunds"));
        assertEquals(LocalDate.of(2027,3,30),result.terminationOptions().proposedLastOccupiedDay());
        assertFalse(result.allowedActions().contains("ACCEPT"));assertTrue(service.get("rental","owner").allowedActions().contains("ACCEPT"));
        verify(invoices,never()).save(any());verifyNoInteractions(payments);
    }
    @Test void onlyLandlordAcceptanceChangesOperationalEndAndNeverExistingBillsOrSnapshots(){
        active();var pending=service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31)));
        assertThrows(AppException.class,()->service.act("rental","tenant","ACCEPT",consent(pending.revision())));
        var accepted=service.act("rental","owner","ACCEPT",consent(pending.revision()));
        assertEquals("APPROVED",accepted.status());assertNull(contract.getAgreedEarlyEndExclusive());assertNull(contract.getAgreedEarlyBillingMonths());
        var approvedRevision=accepted.revision();assertThrows(AppException.class,()->service.act("rental","owner","APPLY_SIGNED",consent(approvedRevision)));
        accepted=service.actSigned("rental","owner",consent(accepted.revision()));
        assertEquals("AGREED",accepted.status());assertEquals(LocalDate.of(2027,3,31),contract.getAgreedEarlyEndExclusive());assertEquals(2,contract.getAgreedEarlyBillingMonths());
        assertNull(contract.getEffectiveLeaseMonths());assertEquals(4,rental.getLeaseMonths());assertEquals("revision",contract.getCurrentRevisionId());
        assertEquals(ContractStatus.ACTIVE,contract.getStatus());assertEquals(LocalDate.of(2027,3,31),accepted.terminationOptions().currentEndExclusive());
        verify(payments).attachOnboardingToContract("rental","lease");verify(invoices,never()).save(any());
        verify(payments,never()).createAgreedRentRefundObligation(any(),any());
        assertThrows(AppException.class,()->service.act("rental","owner","ACCEPT",consent(pending.revision())));
    }
    @Test void ownerCannotCreateNewProposalOrAcceptLegacyOwnerProposal(){
        active();assertThrows(AppException.class,()->service.propose("rental","owner",request(0,LocalDate.of(2027,3,31))));
        workflow.setStatus("PROPOSED");workflow.setProposedBy("owner");workflow.setPayloadJson(support.json(request(0,LocalDate.of(2027,3,31))));
        assertThrows(AppException.class,()->service.act("rental","tenant","ACCEPT",consent(0)));
        assertTrue(service.get("rental","owner").allowedActions().contains("WITHDRAW"));assertTrue(service.get("rental","tenant").allowedActions().contains("REJECT"));
    }
    @Test void landlordRejectionLeavesOriginalEndAndMonthlyCountUntouched(){
        active();var p=service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31)));
        var no=service.act("rental","owner","REJECT",consent(p.revision()));
        assertEquals("REJECTED",no.status());assertNull(contract.getAgreedEarlyEndExclusive());assertNull(contract.getAgreedEarlyBillingMonths());
        assertNull(contract.getMutualTerminationWorkflowId());assertEquals(LocalDate.of(2027,5,31),no.terminationOptions().currentEndExclusive());
        verifyNoInteractions(payments);verify(invoices,never()).save(any());
    }
    @Test void newPartialPolicyAndManualRefundRequestsAreRejected(){
        active();var base=request(0,LocalDate.of(2027,3,31));
        assertThrows(AppException.class,()->service.propose("rental","tenant",new RentalWorkflowPayloads.Termination(0L,base.endExclusive(),base.reason(),null,"DAILY_PRORATED",null,null)));
        var refund=new RentalWorkflowPayloads.RentRefund("source",java.math.BigDecimal.ONE);
        assertThrows(AppException.class,()->service.propose("rental","tenant",new RentalWorkflowPayloads.Termination(0L,base.endExclusive(),base.reason(),null,null,java.util.List.of(refund),null)));
        verifyNoInteractions(payments,records,notices);
    }
    @Test void issuedInvoicePrepayingPastCutoffIsExcludedAndAcceptanceRechecksIt(){
        active();var p=service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31)));
        var bill=com.hs.contract.model.MonthlyInvoice.builder().periodIndex(0).periodEndExclusive(LocalDate.of(2027,2,28))
            .rentPeriodEndExclusive(LocalDate.of(2027,4,30)).status(com.hs.contract.model.MonthlyInvoiceStatus.UNPAID).build();
        when(invoices.findByContractIdOrderByPeriodIndexDesc("lease")).thenReturn(java.util.List.of(bill));
        assertFalse(service.get("rental","owner").allowedActions().contains("ACCEPT"));
        assertThrows(AppException.class,()->service.act("rental","owner","ACCEPT",consent(p.revision())));
        assertNull(contract.getAgreedEarlyEndExclusive());assertEquals("PROPOSED",workflow.getStatus());
        assertEquals(java.util.List.of(LocalDate.of(2027,4,29)),service.get("rental","tenant").terminationOptions().choices().stream().map(com.hs.contract.dto.billing.RentalWorkflowView.Choice::lastOccupiedDay).toList());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=com.hs.payment.model.constant.PaymentStatus.class,names={"CONFIRMED","TRANSFER_REPORTED","AWAITING_TRANSFER","DISPUTED"})
    void existingInitialRentCoverageCannotBeCutOrSilentlyRefunded(com.hs.payment.model.constant.PaymentStatus status){
        active();var initial=com.hs.payment.model.PaymentRequest.builder().id("initial").status(status).build();
        when(paymentRecords.findByRentalRequestIdAndType("rental",com.hs.payment.model.constant.PaymentType.INITIAL)).thenReturn(java.util.Optional.of(initial));
        when(lineRecords.findByPaymentRequestIdOrderBySortOrderAsc("initial")).thenReturn(java.util.List.of(com.hs.payment.model.PaymentLineItem.builder().type("RENT").quantity(3).build()));
        assertThrows(AppException.class,()->service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31))));
        var options=service.get("rental","tenant").terminationOptions().choices();assertEquals(1,options.size());assertEquals(LocalDate.of(2027,4,30),options.getFirst().endExclusive());
        assertNull(contract.getAgreedEarlyEndExclusive());verifyNoInteractions(payments,records,notices);
    }
    @Test void noPermittedDateHasAnExplicitBlockReasonAndNoProposalAction(){
        active();rental.setLeaseMonths(1);var view=service.get("rental","tenant");
        assertTrue(view.terminationOptions().choices().isEmpty());assertFalse(view.allowedActions().contains("PROPOSE"));assertNotNull(view.blockedReason());
    }

    @Test void acceptanceRequiresTheLatestContractSourceAndReviewedVersion(){
        active();var p=service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31)));
        assertThrows(AppException.class,()->service.act("rental","owner","ACCEPT",consent(0)));
        contract.setCurrentRevisionId("new-revision");
        assertThrows(AppException.class,()->service.act("rental","owner","ACCEPT",consent(p.revision())));
        assertNull(contract.getAgreedEarlyEndExclusive());assertEquals("PROPOSED",workflow.getStatus());verifyNoInteractions(payments);
    }
    @Test void anExtremeInvalidDateReturnsDomainErrorWithoutDateArithmeticFailure(){
        active();assertThrows(AppException.class,()->service.propose("rental","tenant",request(0,LocalDate.MIN)));
        verifyNoInteractions(payments,records,notices);
    }

    @Test void missingInitialRentBreakdownFailsClosedButExplicitDepositOnlySourceDoesNot(){
        active();var initial=com.hs.payment.model.PaymentRequest.builder().id("initial").status(com.hs.payment.model.constant.PaymentStatus.CONFIRMED).totalAmount(java.math.BigDecimal.TEN).build();
        when(paymentRecords.findByRentalRequestIdAndType("rental",com.hs.payment.model.constant.PaymentType.INITIAL)).thenReturn(java.util.Optional.of(initial));
        assertThrows(AppException.class,()->service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31))));
        when(lineRecords.findByPaymentRequestIdOrderBySortOrderAsc("initial")).thenReturn(java.util.List.of(com.hs.payment.model.PaymentLineItem.builder().type("DEPOSIT").amount(java.math.BigDecimal.TEN).build()));
        assertEquals("PROPOSED",service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31))).status());
    }
    @Test void orphanMonthlyRentObligationMustBeReconciledBeforeShortening(){
        active();var cash=com.hs.payment.model.PaymentRequest.builder().id("monthly").status(com.hs.payment.model.constant.PaymentStatus.CONFIRMED).invoiceId("missing").build();
        when(paymentRecords.findByRentalRequestIdAndTypeIn("rental",java.util.List.of(com.hs.payment.model.constant.PaymentType.MONTHLY_RENT))).thenReturn(java.util.List.of(cash));
        assertThrows(AppException.class,()->service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31))));
        assertTrue(service.get("rental","tenant").terminationOptions().choices().isEmpty());
        assertNotNull(service.get("rental","tenant").blockedReason());assertNull(contract.getAgreedEarlyEndExclusive());
    }

    @Test void approvalCannotBeEditedAndCanBeWithdrawnBeforeSigningWithoutChangingLease(){
        active();var proposed=service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31)));
        var approved=service.act("rental","owner","ACCEPT",consent(proposed.revision()));assertEquals("APPROVED",approved.status());
        assertNull(contract.getAgreedEarlyEndExclusive());assertFalse(approved.allowedActions().contains("PROPOSE"));
        assertThrows(AppException.class,()->service.propose("rental","tenant",request(approved.revision(),LocalDate.of(2027,4,30))));
        assertEquals("WITHDRAWN",service.act("rental","tenant","WITHDRAW",consent(approved.revision())).status());
        assertNull(contract.getAgreedEarlyEndExclusive());assertNull(contract.getMutualTerminationWorkflowId());
    }
    @Test void effectiveSourceChangeAfterApprovalCannotApplyTheSignedAnnex(){
        active();var proposed=service.propose("rental","tenant",request(0,LocalDate.of(2027,3,31)));
        var approved=service.act("rental","owner","ACCEPT",consent(proposed.revision()));contract.setCurrentRevisionId("new-revision");
        assertThrows(AppException.class,()->service.actSigned("rental","owner",consent(approved.revision())));
        assertNull(contract.getAgreedEarlyEndExclusive());verifyNoInteractions(payments);
    }
}
