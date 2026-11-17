package com.hs.contract.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.payment.model.DisputeCase;
import com.hs.payment.model.constant.DisputeCaseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComplaintNotificationWriterTest {
    final ContractNotificationOutboxRepository outbox=mock(ContractNotificationOutboxRepository.class);
    final ComplaintAdminAudienceRepository admins=mock(ComplaintAdminAudienceRepository.class);
    final ComplaintNotificationWriter writer=new ComplaintNotificationWriter(outbox,admins,"events");
    final List<ContractNotificationOutbox> rows=new ArrayList<>();
    final ObjectMapper json=new ObjectMapper();
    final DisputeCase c=DisputeCase.builder().id("case").subjectType("RENTAL").rentalRequestId("rental")
        .payerId("tenant").payeeId("owner").status(DisputeCaseStatus.OPEN).reason("private complaint text").build();
    @BeforeEach void setup(){when(outbox.save(any())).thenAnswer(call->{var row=(ContractNotificationOutbox)call.getArgument(0);rows.add(row);return row;});}
    void act(String actor,String role,String action){writer.changed(c,actor,role,action,Map.of());}
    List<String> keys(){return rows.stream().map(row->{try{var e=json.readTree(row.getPayload());return e.get("eventType").asText()+":"+e.get("recipients").get(0).asText()+":"+e.get("data").get("audience").asText();}catch(Exception e){throw new AssertionError(e);}}).toList();}

    @Test void opensBilateralCaseWithoutBroadcastingToAdminsAndUrgentCaseReachesEligibleAdminPages(){
        act("tenant","TENANT","OPENED");
        assertEquals(List.of("COMPLAINT_OPENED:owner:PARTY"),keys());verifyNoInteractions(admins);
        rows.clear();c.setStatus(DisputeCaseStatus.ESCALATED);
        when(admins.findIdsAfter(eq("tenant"),eq("owner"),isNull(),any())).thenReturn(List.of("admin-1","owner","tenant"));
        when(admins.findIdsAfter(eq("tenant"),eq("owner"),eq("tenant"),any())).thenReturn(List.of());
        act("owner","LANDLORD","OPENED");
        assertEquals(List.of("COMPLAINT_OPENED:tenant:PARTY","COMPLAINT_OPENED:admin-1:ADMIN"),keys());
        verify(admins).findIdsAfter(eq("tenant"),eq("owner"),isNull(),argThat(p->p.getPageSize()==100&&p.getPageNumber()==0));
    }
    @Test void repliesAndEvidenceNotifyOtherPartyAndOnlyTheActiveAssignedAdmin(){
        c.setStatus(DisputeCaseStatus.ESCALATED);c.setAssignedTo("admin");when(admins.isActiveAdmin("admin")).thenReturn(true);
        writer.changed(c,"tenant","TENANT","COMMENT",Map.of("evidenceIds",List.of("private-evidence")));
        assertEquals(List.of("COMPLAINT_COMMENTED:owner:PARTY","COMPLAINT_COMMENTED:admin:ADMIN"),keys());
        rows.clear();when(admins.isActiveAdmin("admin")).thenReturn(false);act("owner","LANDLORD","COMMENT");
        assertEquals(List.of("COMPLAINT_COMMENTED:tenant:PARTY"),keys());
        rows.clear();c.setAssignedTo("tenant");act("owner","LANDLORD","COMMENT");
        assertEquals(List.of("COMPLAINT_COMMENTED:tenant:PARTY"),keys());
    }
    @Test void escalationAssignmentAndInfoRequestsReachTheirActualAudience(){
        c.setStatus(DisputeCaseStatus.ESCALATED);
        when(admins.findIdsAfter(anyString(),anyString(),isNull(),any())).thenReturn(List.of("admin"));
        act("tenant","TENANT","ESCALATE");
        assertEquals(List.of("COMPLAINT_ESCALATED:owner:PARTY","COMPLAINT_ESCALATED:admin:ADMIN"),keys());
        rows.clear();when(admins.isActiveAdmin("previous")).thenReturn(true);
        writer.changed(c,"admin","ADMIN","CLAIM",Map.of("previousAssignee","previous"));
        assertEquals(List.of("COMPLAINT_ASSIGNED:tenant:PARTY","COMPLAINT_ASSIGNED:owner:PARTY","COMPLAINT_ASSIGNED:previous:ADMIN"),keys());
        rows.clear();writer.changed(c,"admin","ADMIN","REQUEST_INFO",Map.of("requestedFrom","owner"));
        assertEquals(List.of("COMPLAINT_INFO_REQUESTED:owner:PARTY"),keys());
        assertThrows(IllegalStateException.class,()->writer.changed(c,"admin","ADMIN","REQUEST_INFO",Map.of("requestedFrom","outsider")));
        assertEquals(1,rows.size());
    }
    @Test void firstAndFinalConclusionsAreDistinctAndKeepReasonsAndEvidenceOutOfKafka() throws Exception {
        act("admin","ADMIN","COMMENT");act("admin","ADMIN","DECIDE");
        c.setStatus(DisputeCaseStatus.RESOLVED);c.setComplaintDecision("private decision and money details");
        act("admin","ADMIN","DECIDE");
        assertEquals(List.of("COMPLAINT_COMMENTED:tenant:PARTY","COMPLAINT_COMMENTED:owner:PARTY",
            "COMPLAINT_DECIDED:tenant:PARTY","COMPLAINT_DECIDED:owner:PARTY",
            "COMPLAINT_FINAL_DECISION:tenant:PARTY","COMPLAINT_FINAL_DECISION:owner:PARTY"),keys());
        for(var row:rows){JsonNode e=json.readTree(row.getPayload());assertEquals(6,e.size());assertEquals(1,e.get("version").asInt());
            assertEquals(Set.of("caseId","rentalRequestId","audience"),json.convertValue(e.get("data"),Map.class).keySet());
            assertEquals("case",row.getAggregateId());assertFalse(row.getPayload().contains("private"));}
        assertEquals(rows.size(),rows.stream().map(ContractNotificationOutbox::getEventId).distinct().count());
    }
    @Test void closureReopeningWithdrawalAndSourceChangesDoNotBroadcastToUninvolvedUsers(){
        for(String action:List.of("CLOSE","REOPEN","WITHDRAW","SOURCE_ATTACHED","SOURCE_DISPUTED"))act("tenant","TENANT",action);
        assertEquals(List.of("COMPLAINT_CLOSED:owner:PARTY","COMPLAINT_REOPENED:owner:PARTY","COMPLAINT_WITHDRAWN:owner:PARTY",
            "COMPLAINT_SOURCE_UPDATED:owner:PARTY","COMPLAINT_SOURCE_UPDATED:owner:PARTY"),keys());verifyNoInteractions(admins);
        rows.clear();act("SYSTEM","SYSTEM","FINALIZED");
        assertEquals(List.of("COMPLAINT_FINAL_DECISION:tenant:PARTY","COMPLAINT_FINAL_DECISION:owner:PARTY"),keys());
    }
    @Test void invalidActorsAndCasesCannotEnqueueAndStorageFailurePropagates() throws Exception {
        assertThrows(IllegalStateException.class,()->act("outsider","TENANT","COMMENT"));
        assertThrows(IllegalStateException.class,()->act("tenant","ADMIN","DECIDE"));
        c.setSubjectType("PAYMENT");assertThrows(IllegalStateException.class,()->act("tenant","TENANT","OPENED"));assertTrue(rows.isEmpty());
        c.setSubjectType("RENTAL");doThrow(new IllegalStateException("database unavailable")).when(outbox).save(any());
        assertThrows(IllegalStateException.class,()->act("tenant","TENANT","OPENED"));
        var transaction=ComplaintNotificationWriter.class.getMethod("changed",DisputeCase.class,String.class,String.class,String.class,Map.class).getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY,transaction.propagation());
    }
}
