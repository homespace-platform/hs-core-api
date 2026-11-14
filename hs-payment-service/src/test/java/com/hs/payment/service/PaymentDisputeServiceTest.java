package com.hs.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.*;
import com.hs.common.time.BillingTime;
import com.hs.payment.dto.*;
import com.hs.payment.event.PaymentDisputed;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentDisputeServiceTest {
    final DisputeCaseRepository cases=mock(DisputeCaseRepository.class);
    final DisputeCaseEventRepository events=mock(DisputeCaseEventRepository.class);
    final PaymentRequestRepository payments=mock(PaymentRequestRepository.class);
    final PaymentEvidenceRepository evidence=mock(PaymentEvidenceRepository.class);
    final PaymentEventRepository paymentEvents=mock(PaymentEventRepository.class);
    final DepositRecordRepository deposits=mock(DepositRecordRepository.class);
    final RentalRequestRepository rentals=mock(RentalRequestRepository.class);
    final StorageObjectRepository storage=mock(StorageObjectRepository.class);
    final BillingTime time=mock(BillingTime.class);
    final com.hs.listing.notification.RentalRequestNotificationOutboxRepository outbox=mock(com.hs.listing.notification.RentalRequestNotificationOutboxRepository.class);
    final com.hs.payment.notification.PaymentNotificationOutboxRepository paymentOutbox=mock(com.hs.payment.notification.PaymentNotificationOutboxRepository.class);
    final PaymentDisputeService service=new PaymentDisputeService(cases,events,payments,evidence,paymentEvents,deposits,rentals,storage,new ObjectMapper(),time,
        new com.hs.listing.notification.RentalRequestNotificationWriter(outbox,"test.rental.notifications"),
        new com.hs.payment.notification.PaymentNotificationWriter(paymentOutbox,"test.payment.notifications"));
    final List<DisputeCase> rows=new ArrayList<>();
    final List<DisputeCaseEvent> history=new ArrayList<>();
    final List<PaymentEvidence> files=new ArrayList<>();
    final PaymentRequest p=PaymentRequest.builder().id("payment").rentalRequestId("rental").contractId("contract")
        .payerId("tenant").payeeId("owner").type(PaymentType.MONTHLY_RENT).direction(PaymentDirection.TENANT_TO_LANDLORD)
        .status(PaymentStatus.DISPUTED).totalAmount(new BigDecimal("3000000")).transferReference("HSCASE1")
        .dueAt(Instant.parse("2026-11-02T03:00:00Z")).build();
    final Instant now=Instant.parse("2026-11-03T03:00:00Z");
    @BeforeEach void fixture(){
        ReflectionTestUtils.setField(service,"em",mock(jakarta.persistence.EntityManager.class));
        when(time.now()).thenReturn(now);
        when(payments.findById("payment")).thenReturn(Optional.of(p));
        when(payments.findByIdForUpdate("payment")).thenReturn(Optional.of(p));
        when(cases.findByActiveKey(anyString())).thenAnswer(i->rows.stream().filter(c->i.getArgument(0).equals(c.getActiveKey())).findFirst());
        when(cases.saveAndFlush(any())).thenAnswer(i->{DisputeCase c=i.getArgument(0);if(c.getId()==null){c.setId(UUID.randomUUID().toString());rows.add(c);}return c;});
        when(cases.findById(anyString())).thenAnswer(i->rows.stream().filter(c->c.getId().equals(i.getArgument(0))).findFirst());
        when(cases.findByIdForUpdate(anyString())).thenAnswer(i->rows.stream().filter(c->c.getId().equals(i.getArgument(0))).findFirst());
        when(events.save(any())).thenAnswer(i->{DisputeCaseEvent e=i.getArgument(0);history.add(e);return e;});
        when(events.findByCaseIdOrderByRevisionAsc(anyString())).thenAnswer(i->history.stream().filter(e->e.getCaseId().equals(i.getArgument(0))).toList());
        when(evidence.findByPaymentRequestIdOrderByCreatedAtDesc("payment")).thenAnswer(i->new ArrayList<>(files));
        when(evidence.save(any())).thenAnswer(i->{PaymentEvidence e=i.getArgument(0);files.add(e);return e;});
    }
    @AfterEach void clear(){UserContextHolder.clear();SecurityContextHolder.clearContext();}
    DisputeCase open(){service.onDisputed(new PaymentDisputed("payment",p.getPayerId(),"Cần kiểm tra lại khoản chuyển",PaymentStatus.REJECTED,null));return rows.getFirst();}
    DisputeCaseAction action(DisputeCase c,DisputeResolution resolution){return new DisputeCaseAction(c.getRevision(),"Đã đọc chứng cứ và yêu cầu xử lý",resolution,null);}
    DisputeCaseView act(DisputeCase c,String actor,String op,DisputeResolution resolution){return service.act(c.getId(),actor,op,action(c,resolution));}
    void proof(String actor){
        var f=file(actor);when(storage.findById("proof")).thenReturn(Optional.of(f));
        if(rows.isEmpty())files.add(PaymentEvidence.builder().paymentRequestId("payment").uploadedBy(actor).storageObjectId("proof").build());
        else {var c=rows.getLast();service.act(c.getId(),actor,"COMMENT",new DisputeCaseAction(c.getRevision(),"Bổ sung chứng cứ để kiểm tra khoản tiền",null,"proof"));}
    }
    StorageObject file(String actor){return StorageObject.builder().id("proof").ownerId(actor).referenceType("PAYMENT_REQUEST").referenceId("payment")
        .purpose(StoragePurpose.PAYMENT_PROOF).visibility(StorageVisibility.PRIVATE).status(StorageStatus.READY).build();}
    void admin(String actor){UserContextHolder.set(new UserContext(actor,null));SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(actor,"n/a",List.of(new SimpleGrantedAuthority("ADMIN"))));}
    /** Saved rental-request notices as "EVENT:SIDE:recipient" in enqueue order. */
    List<String> notices(){
        var saved=org.mockito.ArgumentCaptor.forClass(com.hs.listing.notification.RentalRequestNotificationOutbox.class);
        verify(outbox,atLeast(0)).save(saved.capture());
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        return saved.getAllValues().stream().map(r->{var e=json.readTree(r.getPayload());
            return e.get("eventType").asText()+":"+e.get("data").get("recipientSide").asText()+":"+e.get("recipients").get(0).asText();}).toList();
    }
    RentalRequest onboardingRental(){
        p.setType(PaymentType.INITIAL);
        var rental=RentalRequest.builder().id("rental").ownerId("owner").renterId("tenant").status(RentalRequestStatus.ACCEPTED)
            .moveInDate(LocalDate.of(2026,11,3)).listing(Listing.builder().status(ListingStatus.RESERVED).build()).build();
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(rental));
        return rental;
    }
    void code(int code,org.junit.jupiter.api.function.Executable call){assertEquals(code,assertThrows(AppException.class,call).getCode());}

    @ParameterizedTest @EnumSource(PaymentType.class)
    void everyExistingPaymentTypeUsesSameCaseAndKeepsOriginalStateAndAmount(PaymentType type){
        p.setType(type);var c=open();assertEquals("PAYMENT",c.getSubjectType());assertEquals("payment",c.getSubjectId());
        assertEquals(PaymentStatus.REJECTED,c.getPreviousPaymentStatus());assertEquals(p.getTotalAmount(),c.getAmountSnapshot());
        assertEquals(p.getPayerId(),c.getOpenedBy());assertEquals(1,c.getRevision());assertEquals(1,history.size());
        verify(payments,never()).save(any());
    }
    @Test void bilateralReviewRestoresConfirmationStepWithoutClaimingMoneyReceived(){
        var c=open();proof("tenant");
        act(c,"tenant","PROPOSE",DisputeResolution.REVIEW_TRANSFER);
        assertEquals(PaymentStatus.DISPUTED,p.getStatus());code(7027,()->act(c,"tenant","ACCEPT",null));
        var view=act(c,"owner","ACCEPT",null);
        assertEquals(DisputeCaseStatus.RESOLVED,view.status());assertEquals(PaymentStatus.TRANSFER_REPORTED,p.getStatus());
        assertNull(p.getConfirmedAt());assertNull(p.getPayeeConfirmedAt());assertNull(p.getPayerReportedAt());
        assertEquals(now.plusSeconds(86400),p.getConfirmationDueAt());assertNull(c.getActiveKey());
        assertEquals(new BigDecimal("3000000"),p.getTotalAmount());verifyNoInteractions(deposits);
        assertEquals("REVIEW_TRANSFER",new ObjectMapper().convertValue(readMetadata(history.getLast()),Map.class).get("resolution"));
        assertEquals(List.of(),notices()); // unrelated (non-onboarding) payment never touches rental-request notifications
    }
    Map<?,?> readMetadata(DisputeCaseEvent e){try{return new ObjectMapper().readValue(e.getMetadataJson(),Map.class);}catch(Exception ex){throw new RuntimeException(ex);}}
    @Test void reviewRequiresValidEvidenceNotJustAnUnrelatedStorageId(){
        var c=open();code(7027,()->act(c,"tenant","PROPOSE",DisputeResolution.REVIEW_TRANSFER));
        proof("tenant");var f=file("intruder");when(storage.findById("proof")).thenReturn(Optional.of(f));
        code(7027,()->act(c,"tenant","PROPOSE",DisputeResolution.REVIEW_TRANSFER));assertEquals(PaymentStatus.DISPUTED,p.getStatus());
    }
    @Test void reopenOverdueMonthlyDebtKeepsDeadlineAndRequiresNewTransferProof(){
        var c=open();Instant due=p.getDueAt();
        act(c,"owner","PROPOSE",DisputeResolution.REOPEN_TRANSFER);act(c,"tenant","ACCEPT",null);
        assertEquals(PaymentStatus.OVERDUE,p.getStatus());assertEquals(due,p.getDueAt());assertNull(p.getConfirmedAt());assertNull(p.getConfirmationDueAt());
    }
    @Test void nonBillingUtilityReopensToStateItsExistingReportApiActuallyAccepts(){
        p.setType(PaymentType.UTILITY);var c=open();act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER);act(c,"owner","ACCEPT",null);
        assertEquals(PaymentStatus.AWAITING_TRANSFER,p.getStatus());
    }
    @ParameterizedTest @EnumSource(DisputeResolution.class)
    void refundResolutionRestoresLedgerWorkflowLabelAndPreservesAllAmounts(DisputeResolution resolution){
        p.setType(PaymentType.DEPOSIT_REFUND);p.setDirection(PaymentDirection.LANDLORD_TO_TENANT);p.setPayerId("owner");p.setPayeeId("tenant");
        var ledger=DepositRecord.builder().refundPaymentRequestId("payment").status(DepositStatus.DISPUTED)
            .heldAmount(new BigDecimal("3000000")).retainedAmount(new BigDecimal("100000")).refundableAmount(new BigDecimal("3000000")).build();
        when(deposits.findByRentalRequestIdForUpdate("rental")).thenReturn(Optional.of(ledger));
        var c=open();proof("owner");act(c,"owner","PROPOSE",resolution);act(c,"tenant","ACCEPT",null);
        assertEquals(resolution==DisputeResolution.REVIEW_TRANSFER?DepositStatus.REFUND_REPORTED:DepositStatus.REFUND_DUE,ledger.getStatus());
        assertEquals(new BigDecimal("3000000"),ledger.getHeldAmount());assertEquals(new BigDecimal("100000"),ledger.getRetainedAmount());
        assertNull(p.getConfirmedAt());assertNull(p.getPayeeConfirmedAt());
    }
    @ParameterizedTest @EnumSource(PaymentDirection.class)
    void depositAdjustmentReconciliationDoesNotApplyDeltaBeforeReceipt(PaymentDirection direction){
        p.setType(PaymentType.DEPOSIT_ADJUSTMENT);p.setDirection(direction);var c=open();
        act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER);act(c,"owner","ACCEPT",null);verifyNoInteractions(deposits);
    }
    @Test void thirdPartiesCannotReadReplyOrEscalate(){
        var c=open();code(7005,()->service.forPayment("payment","outsider"));code(7005,()->act(c,"outsider","ESCALATE",null));assertEquals(1,c.getRevision());
    }
    @Test void declinePreservesHistoryAndAllowsFreshProposalWithoutRewritingRejectedProposal(){
        var c=open();act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER);var proposed=history.getLast().getMetadataJson();
        act(c,"owner","DECLINE",null);assertEquals(DisputeCaseStatus.OPEN,c.getStatus());assertNull(c.getProposedBy());
        act(c,"owner","PROPOSE",DisputeResolution.REOPEN_TRANSFER);assertEquals("owner",c.getProposedBy());assertEquals(proposed,history.get(1).getMetadataJson());
    }
    @Test void staleDoubleAcceptCannotRepeatOrUndoResolution(){
        var c=open();act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER);var payload=action(c,null);
        service.act(c.getId(),"owner","ACCEPT",payload);code(7026,()->service.act(c.getId(),"owner","ACCEPT",payload));
        assertEquals(3,history.size());assertEquals(PaymentStatus.OVERDUE,p.getStatus());
    }
    @Test void notesIncrementRevisionEvenWhenBusinessClockDoesNotMove(){
        var c=open();act(c,"tenant","COMMENT",null);act(c,"owner","COMMENT",null);
        assertEquals(List.of(1L,2L,3L),history.stream().map(DisputeCaseEvent::getRevision).toList());assertEquals(3,c.getRevision());
    }
    @ParameterizedTest @ValueSource(strings={"owner","status","visibility","purpose","reference","active"})
    void evidenceMustBeOwnedReadyPrivateAndBoundToThisPayment(String invalid){
        var c=open();var f=file("tenant");
        switch(invalid){case "owner"->f.setOwnerId("other");case "status"->f.setStatus(StorageStatus.PENDING);case "visibility"->f.setVisibility(StorageVisibility.PUBLIC);
            case "purpose"->f.setPurpose(StoragePurpose.GENERAL);case "reference"->f.setReferenceId("another-payment");case "active"->f.setActive(false);}
        when(storage.findById("proof")).thenReturn(Optional.of(f));
        code(7017,()->service.act(c.getId(),"tenant","COMMENT",new DisputeCaseAction(1L,"Chứng cứ cần kiểm tra lại",null,"proof")));
        assertEquals(1,history.size());assertTrue(files.isEmpty());
    }
    @Test void addingEvidenceAndCommentDoesNotMeanTransferReportedOrReceiptConfirmed(){
        var c=open();when(storage.findById("proof")).thenReturn(Optional.of(file("tenant")));
        var result=service.act(c.getId(),"tenant","COMMENT",new DisputeCaseAction(1L,"Chứng cứ chuyển tiền cần kiểm tra",null,"proof"));
        assertEquals(1,result.evidences().size());assertEquals("proof",history.getLast().getEvidenceStorageId());assertEquals(PaymentStatus.DISPUTED,p.getStatus());
        verify(payments,never()).save(any());
    }
    @Test void escalationStopsOldPartyProposalAndRequiresAdminToClaimBeforeDecision(){
        var c=open();act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER);act(c,"owner","ESCALATE",null);
        code(7027,()->act(c,"owner","ACCEPT",null));code(7027,()->act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER));
        code(7005,()->service.adminGet(c.getId(),"admin"));admin("admin");
        code(7027,()->service.adminAct(c.getId(),"admin","DECIDE",action(c,DisputeResolution.REOPEN_TRANSFER)));
        service.adminAct(c.getId(),"admin","CLAIM",action(c,null));
        service.adminAct(c.getId(),"admin","DECIDE",action(c,DisputeResolution.REOPEN_TRANSFER));
        assertEquals(DisputeCaseStatus.RESOLVED,c.getStatus());assertEquals("admin",c.getResolvedBy());assertNull(p.getConfirmedAt());
        assertEquals("ADMIN",history.getLast().getActorRole());
    }
    @Test void adminsCannotAdjudicateTheirOwnRentalOrUseAnotherAdminsIdentity(){
        var c=open();act(c,"tenant","ESCALATE",null);admin("tenant");
        code(7027,()->service.adminAct(c.getId(),"tenant","CLAIM",action(c,null)));
        code(7005,()->service.adminAct(c.getId(),"admin","CLAIM",action(c,null)));
    }
    @Test void anotherAdminNeedsFreshVersionAndExplicitReassignment(){
        var c=open();act(c,"tenant","ESCALATE",null);var old=action(c,null);admin("first");service.adminAct(c.getId(),"first","CLAIM",old);
        admin("second");code(7026,()->service.adminAct(c.getId(),"second","CLAIM",old));
        code(7027,()->service.adminAct(c.getId(),"second","DECIDE",action(c,DisputeResolution.REOPEN_TRANSFER)));
        service.adminAct(c.getId(),"second","CLAIM",action(c,null));assertEquals("second",c.getAssignedTo());
    }
    @Test void changedDebtAndConfirmedPaymentCannotBeOverwrittenByOldDecision(){
        var c=open();act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER);p.setTotalAmount(BigDecimal.ONE);
        code(7027,()->act(c,"owner","ACCEPT",null));p.setTotalAmount(c.getAmountSnapshot());p.setStatus(PaymentStatus.CONFIRMED);
        code(7027,()->act(c,"owner","ACCEPT",null));
    }
    @Test void legacyCaseKeepsUnknownPriorStateExplicitAndAdoptionIsIdempotent(){
        var adopted=service.adoptLegacy("payment","tenant","Tiếp nhận khoản đối soát cũ");
        assertNull(adopted.previousPaymentStatus());assertEquals("LEGACY_ADOPTED",adopted.events().getFirst().action());
        assertEquals(adopted.id(),service.adoptLegacy("payment","owner","Đọc lại khoản đối soát cũ").id());assertEquals(1,rows.size());
    }
    @Test void laterEvidenceCannotBeShownAsGroundsForAnEarlierResolvedCase(){
        var c=open();proof("tenant");act(c,"tenant","PROPOSE",DisputeResolution.REVIEW_TRANSFER);act(c,"owner","ACCEPT",null);
        files.add(PaymentEvidence.builder().paymentRequestId("payment").uploadedBy("tenant").storageObjectId("future-proof").build());
        admin("admin");var original=service.adminGet(c.getId(),"admin");
        assertEquals(List.of("proof"),original.evidences().stream().map(DisputeCaseView.Evidence::storageId).toList());
    }
    @Test void onboardingReopensOnlyActiveReservedRentalAndDoesNotChangeMoveInOrPrice(){
        p.setType(PaymentType.INITIAL);
        var rental=RentalRequest.builder().id("rental").ownerId("owner").renterId("tenant").status(RentalRequestStatus.ACCEPTED)
            .moveInDate(LocalDate.of(2026,11,3)).listing(Listing.builder().status(ListingStatus.RESERVED).build()).build();
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(rental));
        var c=open();act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER);
        rental.setStatus(RentalRequestStatus.CANCELLED_BY_OWNER);code(7027,()->act(c,"owner","ACCEPT",null));
        rental.setStatus(RentalRequestStatus.ACCEPTED);act(c,"owner","ACCEPT",null);
        assertEquals(now.plusSeconds(86400),p.getDueAt());assertEquals(p.getDueAt(),rental.getHoldExpiresAt());assertEquals(LocalDate.of(2026,11,3),rental.getMoveInDate());
        // Blocked attempt notified nobody; the accepting owner is suppressed, only the renter hears about the new deadline.
        assertEquals(List.of("RENTAL_HOLD_EXTENDED:RENTER:tenant"),notices());
    }
    @Test void onboardingReopenWithUnchangedDeadlineAndReviewRouteSendNothing(){
        var rental=onboardingRental();rental.setHoldExpiresAt(now.plusSeconds(86400)); // reopen window equals the current deadline
        var c=open();act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER);act(c,"owner","ACCEPT",null);
        assertEquals(now.plusSeconds(86400),rental.getHoldExpiresAt());assertEquals(List.of(),notices());
    }
    @Test void onboardingReviewTransferDoesNotMoveHoldOrNotify(){
        var rental=onboardingRental();var held=rental.getHoldExpiresAt();
        var c=open();proof("tenant");act(c,"tenant","PROPOSE",DisputeResolution.REVIEW_TRANSFER);act(c,"owner","ACCEPT",null);
        assertEquals(PaymentStatus.TRANSFER_REPORTED,p.getStatus());assertEquals(held,rental.getHoldExpiresAt());assertEquals(List.of(),notices());
    }
    @Test void adminDecisionExtendingHoldNotifiesBothPersistedParties(){
        var rental=onboardingRental();
        var c=open();act(c,"tenant","ESCALATE",null);admin("admin");
        service.adminAct(c.getId(),"admin","CLAIM",action(c,null));
        service.adminAct(c.getId(),"admin","DECIDE",action(c,DisputeResolution.REOPEN_TRANSFER));
        assertEquals(now.plusSeconds(86400),rental.getHoldExpiresAt());
        assertEquals(List.of("RENTAL_HOLD_EXTENDED:OWNER:owner","RENTAL_HOLD_EXTENDED:RENTER:tenant"),notices());
    }

    List<String> paymentNotices(){
        var saved=org.mockito.ArgumentCaptor.forClass(com.hs.payment.notification.PaymentNotificationOutbox.class);
        verify(paymentOutbox,atLeast(0)).save(saved.capture());var json=tools.jackson.databind.json.JsonMapper.builder().build();
        return saved.getAllValues().stream().map(row->{var e=json.readTree(row.getPayload());
            assertEquals(1,e.get("recipients").size());assertEquals(1,e.get("data").size());
            assertEquals("payment",e.get("data").get("paymentId").asText());
            return e.get("eventType").asText()+":"+e.get("recipients").get(0).asText();}).toList();
    }
    @Test void evidenceProposalDeclineAndEscalationNotifyOnlyCounterpartyWhileNotesAndDuplicateEvidenceStaySilent(){
        var c=open();assertEquals(List.of(),paymentNotices());
        act(c,"tenant","COMMENT",null);assertEquals(List.of(),paymentNotices());
        proof("tenant");proof("tenant");
        act(c,"owner","PROPOSE",DisputeResolution.REOPEN_TRANSFER);act(c,"tenant","DECLINE",null);
        act(c,"tenant","ESCALATE",null);
        assertEquals(List.of("PAYMENT_DISPUTE_EVIDENCE_ADDED:owner","PAYMENT_DISPUTE_PROPOSED:tenant",
            "PAYMENT_DISPUTE_PROPOSAL_DECLINED:owner","PAYMENT_DISPUTE_ESCALATED:owner"),paymentNotices());
        admin("admin");service.adminAct(c.getId(),"admin","CLAIM",action(c,null));
        assertEquals(4,paymentNotices().size());service.adminAct(c.getId(),"admin","DECIDE",action(c,DisputeResolution.REVIEW_TRANSFER));
        assertEquals(List.of("PAYMENT_DISPUTE_REVIEW_TRANSFER:tenant","PAYMENT_DISPUTE_REVIEW_TRANSFER:owner"),paymentNotices().subList(4,6));
        assertNull(p.getConfirmedAt());
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void reopenNoticeIsSuppressedOnlyWhenTheSameActionActuallyQueuesHoldExtension(boolean changed){
        var rental=onboardingRental();if(!changed)rental.setHoldExpiresAt(now.plusSeconds(86400));
        var c=open();act(c,"tenant","PROPOSE",DisputeResolution.REOPEN_TRANSFER);
        clearInvocations(paymentOutbox,outbox);var accepted=action(c,null);service.act(c.getId(),"owner","ACCEPT",accepted);
        assertEquals(changed?List.of():List.of("PAYMENT_DISPUTE_REOPENED:tenant"),paymentNotices());
        assertEquals(changed?List.of("RENTAL_HOLD_EXTENDED:RENTER:tenant"):List.of(),notices());
        code(7026,()->service.act(c.getId(),"owner","ACCEPT",accepted));
        assertEquals(changed?0:1,paymentNotices().size());
    }
    @Test void reversedRefundProposalAndAcceptedReviewReachTheActualOtherParty(){
        p.setType(PaymentType.INITIAL_PAYMENT_REFUND);p.setDirection(PaymentDirection.LANDLORD_TO_TENANT);
        p.setPayerId("owner");p.setPayeeId("tenant");var c=open();proof("owner");
        act(c,"owner","PROPOSE",DisputeResolution.REVIEW_TRANSFER);act(c,"tenant","ACCEPT",null);
        assertEquals(List.of("PAYMENT_DISPUTE_EVIDENCE_ADDED:tenant","PAYMENT_DISPUTE_PROPOSED:tenant",
            "PAYMENT_DISPUTE_REVIEW_TRANSFER:owner"),paymentNotices());assertNull(p.getConfirmedAt());
    }
}
