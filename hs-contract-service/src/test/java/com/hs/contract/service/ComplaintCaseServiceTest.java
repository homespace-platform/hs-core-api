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
    private final ComplaintCaseService service = new ComplaintCaseService(
            cases, events, rentals, contracts, closures, storage,
            new ObjectMapper().findAndRegisterModules(), time);
    private DisputeCase complaint;
    private final Instant now = Instant.parse("2026-12-13T03:00:00Z");

    @BeforeEach void fixture() {
        ReflectionTestUtils.setField(service, "em", mock(EntityManager.class));
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
        when(events.findByCaseIdOrderByRevisionAsc("case")).thenReturn(List.of());
        admin("admin");
    }

    @AfterEach void clearIdentity() {
        UserContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void admin(String id) {
        UserContextHolder.set(new UserContext(id, null));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                id, "n/a", List.of(new SimpleGrantedAuthority("ADMIN"))));
    }

    private ComplaintAction action(long revision) {
        return new ComplaintAction(revision, "Đã đọc hồ sơ và tiếp nhận xử lý", null, null, null, null);
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
        assertEquals(List.of("COMMENT","ESCALATE","WITHDRAW"),service.get("case","tenant").allowedActions());
        var result=service.act("case","tenant","ESCALATE",action(7));
        assertEquals("WAITING_ADMIN",result.stage());assertNull(result.proposedBy());
        verify(contracts,never()).save(any());verify(closures,never()).save(any());
    }
    @Test void removedAgreementAndMoneyActionsAreRejected() {
        complaint.setStatus(DisputeCaseStatus.OPEN);
        for(var action:List.of("PROPOSE","ACCEPT","DECLINE","APPROVE_SETTLEMENT","CONFIRM_COMPLETE","REJECT_DECISION"))
            assertThrows(AppException.class,()->service.act("case","tenant",action,action(7)));
        assertEquals(7,complaint.getRevision());verify(events,never()).save(any());
    }
    @Test void adminConclusionClosesOnlyTheSupportCaseAndCanBeReopened() {
        complaint.setAssignedTo("admin");complaint.setComplaintStage("UNDER_REVIEW");
        var closed=service.adminAct("case","admin","DECIDE",new ComplaintAction(7L,"Đã đối chiếu và hướng dẫn hai bên sửa khóa",null,"RECORD_FINDING",null,null));
        assertEquals(DisputeCaseStatus.RESOLVED,closed.status());assertEquals("CLOSED",closed.stage());
        assertEquals("RECORD_FINDING",closed.outcome());assertNull(closed.respondBy());
        verify(contracts,never()).save(any());verify(closures,never()).save(any());
        var reopened=service.act("case","tenant","REOPEN",action(8));
        assertEquals(DisputeCaseStatus.OPEN,reopened.status());assertNull(reopened.decision());
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
        assertEquals(DisputeCaseStatus.RESOLVED,service.adminAct("case","admin","DECIDE",action(7)).status());
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
}
