package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.complaint.ComplaintAction;
import com.hs.contract.dto.complaint.ComplaintCategory;
import com.hs.contract.model.Contract;
import com.hs.contract.repository.ContractClosureRepository;
import com.hs.contract.repository.ContractRepository;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.DisputeCase;
import com.hs.payment.model.DisputeCaseEvent;
import com.hs.payment.model.constant.DisputeCaseStatus;
import com.hs.payment.repository.DisputeCaseEventRepository;
import com.hs.payment.repository.DisputeCaseRepository;
import com.hs.storage.repository.StorageObjectRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ComplaintCaseServiceTest {
    private final DisputeCaseRepository cases = mock(DisputeCaseRepository.class);
    private final DisputeCaseEventRepository events = mock(DisputeCaseEventRepository.class);
    private final RentalRequestRepository rentals = mock(RentalRequestRepository.class);
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final ContractClosureRepository closures = mock(ContractClosureRepository.class);
    private final StorageObjectRepository storage = mock(StorageObjectRepository.class);
    private final BillingTime time = mock(BillingTime.class);
    private final com.hs.contract.notification.ComplaintNotificationWriter notifications=mock(com.hs.contract.notification.ComplaintNotificationWriter.class);
    private final ComplaintCaseService service = new ComplaintCaseService(
            cases, events, rentals, contracts, closures, storage,
            new ObjectMapper().findAndRegisterModules(), time);
    private DisputeCase complaint;
    private final java.util.ArrayList<DisputeCaseEvent> audit=new java.util.ArrayList<>();
    private final Instant now = Instant.parse("2026-12-13T03:00:00Z");

    @BeforeEach void fixture() {
        ReflectionTestUtils.setField(service, "em", mock(EntityManager.class));
        ReflectionTestUtils.setField(service,"notifications",notifications);
        when(time.now()).thenReturn(now);
        var rental = RentalRequest.builder().id("rental").ownerId("owner").renterId("tenant").build();
        var contract = Contract.builder().id("contract").rentalRequestId("rental")
                .landlordId("owner").tenantId("tenant").build();
        complaint = DisputeCase.builder().id("case").subjectType("RENTAL").subjectId("rental")
                .rentalRequestId("rental").contractId("contract").payerId("tenant").payeeId("owner")
                .openedBy("tenant").reason("Cần sửa khóa phòng theo thỏa thuận").openedAt(now)
                .status(DisputeCaseStatus.ESCALATED).complaintCategory(ComplaintCategory.REPAIR.name())
                .complaintStage("WAITING_ADMIN").revision(7).amountSnapshot(BigDecimal.ZERO)
                .currency("VND").initialEvidenceSnapshot("[]").respondBy(now.plusSeconds(3600))
                .responseDueFor("ADMIN").build();
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));
        when(contracts.findById("contract")).thenReturn(Optional.of(contract));
        when(cases.findById("case")).thenReturn(Optional.of(complaint));
        when(cases.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(events.findByCaseIdOrderByRevisionAsc("case")).thenReturn(audit);
        when(events.save(any())).thenAnswer(call->{DisputeCaseEvent event=call.getArgument(0);audit.add(event);return event;});
        admin("admin");
    }

    @AfterEach void clearIdentity() {
        UserContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test void notificationHooksFollowTheTwoConclusionRoundsAndARepeatedClaimIsSilent() {
        var notices=new java.util.ArrayList<String>();
        doAnswer(call->{notices.add(call.getArgument(3)+":"+complaint.getStatus());return null;})
            .when(notifications).changed(any(),any(),any(),any(),any());
        service.adminAct("case","admin","CLAIM",action(7));
        service.adminAct("case","admin","CLAIM",action(complaint.getRevision()));
        var first=decide();
        service.act("case","tenant","COMMENT",action(first.revision()));
        service.act("case","owner","ESCALATE",action(complaint.getRevision()));
        service.adminAct("case","admin","CLAIM",action(complaint.getRevision()));
        decide();
        assertEquals(List.of("CLAIM:ESCALATED","DECIDE:OPEN","COMMENT:OPEN","ESCALATE:ESCALATED","CLAIM:ESCALATED","DECIDE:RESOLVED"),notices);
        assertThrows(AppException.class,()->service.act("case","tenant","REOPEN",action(complaint.getRevision())));
        assertEquals(6,notices.size());
    }

    @Test void openingFromWorkflowAnnouncesOneNewCaseAndDoesNotAlsoAnnounceItsInitialSourceAttachment() {
        var workflows=mock(com.hs.contract.repository.RentalWorkflowRecordRepository.class);
        ReflectionTestUtils.setField(service,"workflows",workflows);
        var source=new com.hs.contract.model.RentalWorkflowRecord();source.setId("rental:EARLY_EXIT");
        source.setRentalRequestId("rental");source.setContractId("contract");source.setKind("EARLY_EXIT");source.setStatus("AGREED");source.setRevision(4);
        when(workflows.findById(source.getId())).thenReturn(Optional.of(source));
        service.openFromWorkflow("rental","tenant","EARLY_EXIT",4,"Cần hỗ trợ thực hiện việc trả nhà");
        verify(notifications).changed(any(),eq("tenant"),eq("TENANT"),eq("OPENED"),any());
        verifyNoMoreInteractions(notifications);
    }

    private void admin(String id) {
        UserContextHolder.set(new UserContext(id, null));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                id, "n/a", List.of(new SimpleGrantedAuthority("ADMIN"),new SimpleGrantedAuthority("COMPLAINT_VIEW"),new SimpleGrantedAuthority("COMPLAINT_RESOLVE"))));
    }

    private ComplaintAction action(long revision) {
        return new ComplaintAction(revision, "Đã đọc hồ sơ và tiếp nhận xử lý", null, null, null, null);
    }

    @Test void adminRoleNeedsComplaintPermissionToReadAndResolve() {
        withAuthorities("ADMIN");
        assertThrows(AppException.class, () -> service.adminList("admin", null, null, null, 1, 10));
        assertThrows(AppException.class, () -> service.adminGet("case", "admin"));
        assertThrows(AppException.class, () -> service.adminAct("case", "admin", "CLAIM", action(7)));
        withAuthorities("ADMIN", "COMPLAINT_VIEW");
        assertDoesNotThrow(() -> service.adminGet("case", "admin"));
        assertThrows(AppException.class, () -> service.adminAct("case", "admin", "CLAIM", action(7)));
        withAuthorities("COMPLAINT_VIEW", "COMPLAINT_RESOLVE");
        assertThrows(AppException.class, () -> service.adminGet("case", "admin"));
    }

    private void withAuthorities(String... authorities) {
        UserContextHolder.set(new UserContext("admin", null));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", "n/a", java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test void firstClaimAssignsAdminAndStartsReviewWithAudit() {
        var result = service.adminAct("case", "admin", "CLAIM", action(7));
        assertEquals("admin", result.assignedTo());
        assertEquals("UNDER_REVIEW", result.stage());
        assertEquals(8, result.revision());
        assertEquals(now.plusSeconds(48 * 3600), result.respondBy());
        assertFalse(result.allowedActions().contains("CLAIM"));
        assertTrue(result.allowedActions().contains("DECIDE"));
        verify(cases).saveAndFlush(complaint);
        verify(events).save(any());
        verifyNoInteractions(storage);
    }

    @Test void retryingClaimCannotErasePendingInformationRequestOrExtendOverdueDeadline() {
        complaint.setAssignedTo("admin");
        complaint.setComplaintStage("WAITING_INFO");
        complaint.setResponseDueFor("tenant");
        var deadline = now.minusSeconds(1);
        complaint.setRespondBy(deadline);
        var result = service.adminAct("case", "admin", "CLAIM", action(7));
        assertEquals("WAITING_INFO", result.stage());
        assertEquals("tenant", result.responseDueFor());
        assertEquals(deadline, result.respondBy());
        assertTrue(result.overdue());
        assertEquals(7, result.revision());
        assertFalse(result.allowedActions().contains("CLAIM"));
        assertTrue(result.allowedActions().contains("DECIDE"));
        verify(cases, never()).saveAndFlush(any());
        verify(events, never()).save(any());
        verifyNoInteractions(storage);
    }

    @Test void retryingClaimPreservesFollowUpDecisionAndCompletionAcknowledgements() {
        complaint.setAssignedTo("admin");
        complaint.setComplaintStage("FOLLOW_UP");
        complaint.setComplaintOutcome("RECORD_FINDING");
        complaint.setComplaintDecision("Chủ nhà sửa khóa và khách xác nhận hiện trạng");
        complaint.setTenantCompletedAt(now);
        var deadline = complaint.getRespondBy();
        var result = service.adminAct("case", "admin", "CLAIM", action(7));
        assertEquals("FOLLOW_UP", result.stage());
        assertEquals("RECORD_FINDING", result.outcome());
        assertEquals(complaint.getComplaintDecision(), result.decision());
        assertTrue(result.tenantCompleted());
        assertEquals(deadline, result.respondBy());
        assertEquals(7, result.revision());
        verify(events, never()).save(any());
    }

    @Test void staleClaimCannotCircumventRevisionCheckEvenForCurrentAssignee() {
        complaint.setAssignedTo("admin");
        var error = assertThrows(AppException.class,
                () -> service.adminAct("case", "admin", "CLAIM", action(6)));
        assertEquals(6075, error.getCode());
        assertEquals(7, complaint.getRevision());
        verify(events, never()).save(any());
    }

    @Test void anotherAdministratorCanTakeOverWithoutErasingRequestedInformationOrItsDeadline() {
        complaint.setAssignedTo("previous-admin");
        complaint.setComplaintStage("WAITING_INFO");
        complaint.setResponseDueFor("tenant");
        var deadline = now.minusSeconds(1);
        complaint.setRespondBy(deadline);
        var before = service.adminGet("case", "admin");
        assertTrue(before.allowedActions().contains("CLAIM"));
        var result = service.adminAct("case", "admin", "CLAIM", action(7));
        assertEquals("admin", result.assignedTo());
        assertEquals(8, result.revision());
        assertEquals("WAITING_INFO", result.stage());
        assertEquals("tenant", result.responseDueFor());
        assertEquals(deadline, result.respondBy());
        assertTrue(result.overdue());
        assertTrue(result.allowedActions().contains("DECIDE"));
        verify(events).save(argThat(event -> event.getMetadataJson().contains("previous-admin")));
    }

    @Test void transferringAnUnderReviewCaseCannotRestartItsResponseClock() {
        complaint.setAssignedTo("previous-admin");
        complaint.setComplaintStage("UNDER_REVIEW");
        var deadline = complaint.getRespondBy();
        var result = service.adminAct("case", "admin", "CLAIM", action(7));
        assertEquals("UNDER_REVIEW", result.stage());
        assertEquals("ADMIN", result.responseDueFor());
        assertEquals(deadline, result.respondBy());
        assertTrue(result.allowedActions().contains("DECIDE"));
        verify(events).save(any());
    }

    @Test void administratorWhoIsAContractPartyCannotClaimTheirOwnCase() {
        admin("owner");
        assertThrows(AppException.class, () -> service.adminAct("case", "owner", "CLAIM", action(7)));
        assertNull(complaint.getAssignedTo());
        verify(events, never()).save(any());
        verifyNoInteractions(storage);
    }

    @Test void ordinaryPartyCanEscalateWithoutAnAgreementWorkflow() {
        complaint.setStatus(DisputeCaseStatus.OPEN);complaint.setComplaintStage("WAITING_RESPONSE");
        assertEquals(List.of("COMMENT","ESCALATE","CLOSE"),service.get("case","tenant").allowedActions());
        var result=service.act("case","tenant","ESCALATE",action(7));
        assertEquals("WAITING_ADMIN",result.stage());assertNull(result.proposedBy());
        verify(contracts,never()).save(any());verify(closures,never()).save(any());
    }
    @Test void removedAgreementAndMoneyActionsAreRejected() {
        complaint.setStatus(DisputeCaseStatus.OPEN);
        for(var action:List.of("PROPOSE","ACCEPT","DECLINE","APPROVE_SETTLEMENT","CONFIRM_COMPLETE","REJECT_DECISION"))
            assertThrows(AppException.class,()->service.act("case","tenant",action,action(7)));
        assertEquals(7,complaint.getRevision());verify(events,never()).save(any());verifyNoInteractions(notifications);
    }
    private com.hs.contract.dto.complaint.ComplaintView decide(){
        return service.adminAct("case","admin","DECIDE",action(complaint.getRevision()));
    }
    private void readyReview(){complaint.setAssignedTo("admin");complaint.setComplaintStage("UNDER_REVIEW");}
    @Test void firstConclusionAllowsDiscussionAndOnlyAnExplicitSecondEscalationLetsAdminActAgain() {
        readyReview();var first=decide();
        assertEquals(DisputeCaseStatus.OPEN,first.status());assertEquals("FOLLOW_UP",first.stage());assertEquals(1,first.adminReviewCount());
        assertEquals(2,first.adminReviewLimit());assertFalse(first.finalClosed());assertNull(first.assignedTo());assertNull(first.respondBy());
        assertEquals(List.of("COMMENT","ESCALATE","CLOSE"),service.get("case","tenant").allowedActions());
        assertTrue(service.adminGet("case","admin").allowedActions().isEmpty());
        assertThrows(AppException.class,this::decide);
        var comment=service.act("case","tenant","COMMENT",action(first.revision()));assertEquals(1,comment.adminReviewCount());
        var next=service.act("case","owner","ESCALATE",action(comment.revision()));assertEquals("WAITING_ADMIN",next.stage());
        assertEquals(1,next.adminReviewCount());assertNull(next.assignedTo());
        service.adminAct("case","admin","CLAIM",action(next.revision()));
        var last=decide();assertEquals(DisputeCaseStatus.RESOLVED,last.status());assertTrue(last.finalClosed());assertEquals(2,last.adminReviewCount());
        assertTrue(last.allowedActions().isEmpty());assertNull(last.respondBy());
        verify(contracts,never()).save(any());verify(closures,never()).save(any());
    }
    @Test void adminCannotSubmitASettlementRevisionEvenWithAValidCaseRevision() {
        complaint.setAssignedTo("admin");complaint.setComplaintStage("UNDER_REVIEW");
        assertThrows(AppException.class,()->service.adminAct("case","admin","DECIDE",new ComplaintAction(7L,"Đề xuất sửa khoản khấu trừ theo hồ sơ",null,"REVISE_SETTLEMENT",null,null)));
        var settlement=new com.hs.contract.dto.billing.ClosurePayload(null,null,BigDecimal.ZERO,"Không được đổi quyết toán từ khiếu nại",true,List.of(),1);
        assertThrows(AppException.class,()->service.adminAct("case","admin","DECIDE",new ComplaintAction(7L,"Đã kiểm tra đầy đủ chứng cứ hai bên",null,"RECORD_FINDING",null,settlement)));
        assertThrows(AppException.class,()->service.act("case","tenant","COMMENT",new ComplaintAction(7L,"Đã kiểm tra đầy đủ chứng cứ hai bên",null,null,null,settlement)));
        verify(events,never()).save(any());verify(contracts,never()).save(any());verify(closures,never()).save(any());
    }
    @Test void adminReplyPreservesPendingParticipantAndDeadline() {
        complaint.setAssignedTo("admin");complaint.setComplaintStage("WAITING_INFO");complaint.setResponseDueFor("tenant");
        var due=complaint.getRespondBy();var result=service.adminAct("case","admin","COMMENT",action(7));
        assertEquals("WAITING_INFO",result.stage());assertEquals("tenant",result.responseDueFor());assertEquals(due,result.respondBy());
    }
    @Test void adminWaitsForRequestedInformationUntilDeadline() {
        complaint.setAssignedTo("admin");complaint.setComplaintStage("WAITING_INFO");complaint.setResponseDueFor("tenant");
        assertThrows(AppException.class,()->service.adminAct("case","admin","DECIDE",action(7)));
        complaint.setRespondBy(now);
        assertEquals(DisputeCaseStatus.OPEN,service.adminAct("case","admin","DECIDE",action(7)).status());
    }
    @Test void adminReplyRequiresClaimAndCannotBeForgedByAParty() {
        assertThrows(AppException.class,()->service.adminAct("case","admin","COMMENT",action(7)));
        admin("tenant");assertThrows(AppException.class,()->service.adminAct("case","tenant","COMMENT",action(7)));
        verify(events,never()).save(any());
    }
    @Test void readingLegacySettlementComplaintDoesNotMutateContractOrCase() {
        complaint.setComplaintStage("FOLLOW_UP");complaint.setComplaintOutcome("REVISE_SETTLEMENT");
        complaint.setAssignedTo("admin");complaint.setComplaintCategory("DEPOSIT_SETTLEMENT");
        assertEquals(List.of("COMMENT"),service.get("case","tenant").allowedActions());
        assertTrue(service.adminGet("case","admin").allowedActions().contains("DECIDE"));
        verify(events,never()).save(any());verify(contracts,never()).save(any());verify(closures,never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"tenant","owner"})
    void eitherPartyCanCloseASelfResolvedIssue(String actor){
        complaint.setStatus(DisputeCaseStatus.OPEN);complaint.setComplaintStage("DISCUSSING");
        assertTrue(service.get("case",actor).allowedActions().contains("CLOSE"));
        var result=service.act("case",actor,"CLOSE",action(7));
        assertEquals(DisputeCaseStatus.RESOLVED,result.status());assertEquals(0,result.adminReviewCount());assertFalse(result.finalClosed());
        assertEquals(List.of("REOPEN"),result.allowedActions());verify(contracts,never()).save(any());verify(closures,never()).save(any());
    }
    @Test void closingAndReopeningAfterFirstConclusionDoesNotResetTheBudget(){
        readyReview();var first=decide();
        var closed=service.act("case","owner","CLOSE",action(first.revision()));assertEquals(1,closed.adminReviewCount());
        var reopened=service.act("case","tenant","REOPEN",action(closed.revision()));assertEquals(1,reopened.adminReviewCount());
        var next=service.act("case","tenant","ESCALATE",action(reopened.revision()));
        service.adminAct("case","admin","CLAIM",action(next.revision()));assertTrue(decide().finalClosed());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"COMMENT","ESCALATE","CLOSE","WITHDRAW","REOPEN"})
    void noPartyMutationIsAllowedAfterTheSecondConclusion(String action){
        audit.add(DisputeCaseEvent.builder().actorRole("ADMIN").action("DECIDE").revision(1).build());
        readyReview();var last=decide();assertTrue(last.finalClosed());
        assertThrows(AppException.class,()->service.act("case","tenant",action,action(last.revision())));
        assertEquals(2,service.get("case","owner").adminReviewCount());assertTrue(service.get("case","owner").allowedActions().isEmpty());
    }
    @Test void noAdminCanClaimReplyOrConcludeAfterTheSecondConclusion(){
        audit.add(DisputeCaseEvent.builder().actorRole("ADMIN").action("DECIDE").revision(1).build());readyReview();decide();
        admin("another-admin");
        for(String action:List.of("CLAIM","COMMENT","REQUEST_INFO","DECIDE"))
            assertThrows(AppException.class,()->service.adminAct("case","another-admin",action,action(complaint.getRevision())));
        assertEquals(2,audit.stream().filter(e->"DECIDE".equals(e.getAction())).count());
    }
    @Test void commentsClaimsAndReassignmentDoNotConsumeAdminConclusions(){
        service.adminAct("case","admin","CLAIM",action(7));
        service.adminAct("case","admin","COMMENT",action(8));
        admin("another-admin");var result=service.adminAct("case","another-admin","CLAIM",action(9));
        assertEquals(0,result.adminReviewCount());assertEquals("UNDER_REVIEW",result.stage());
    }
    @Test void staleSecondConclusionCannotConsumeAnotherReview(){
        audit.add(DisputeCaseEvent.builder().actorRole("ADMIN").action("DECIDE").revision(1).build());readyReview();
        var last=decide();assertThrows(AppException.class,()->service.adminAct("case","admin","DECIDE",action(last.revision()-1)));
        assertEquals(2,service.get("case","tenant").adminReviewCount());
    }
    @Test void unrelatedPartyCannotCloseTheComplaint(){
        complaint.setStatus(DisputeCaseStatus.OPEN);
        assertThrows(AppException.class,()->service.act("case","stranger","CLOSE",action(7)));
        verify(events,never()).save(any());
    }

    @Test void readingLegacyActiveCaseWithTwoConclusionsFinalizesItWithoutChangingMoney(){
        audit.add(DisputeCaseEvent.builder().actorRole("ADMIN").action("DECIDE").revision(1).build());
        audit.add(DisputeCaseEvent.builder().actorRole("ADMIN").action("DECIDE").revision(2).build());
        var result=service.get("case","tenant");assertTrue(result.finalClosed());assertEquals(DisputeCaseStatus.RESOLVED,result.status());
        assertEquals(2,result.adminReviewCount());assertTrue(result.allowedActions().isEmpty());
        verify(contracts,never()).save(any());verify(closures,never()).save(any());
    }
}
