package com.hs.contract.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.payment.model.DisputeCase;
import com.hs.payment.model.constant.DisputeCaseStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

@Service
public class ComplaintNotificationWriter {
    public enum Event {
        COMPLAINT_OPENED, COMPLAINT_COMMENTED, COMPLAINT_ESCALATED, COMPLAINT_ASSIGNED,
        COMPLAINT_INFO_REQUESTED, COMPLAINT_DECIDED, COMPLAINT_FINAL_DECISION,
        COMPLAINT_CLOSED, COMPLAINT_REOPENED, COMPLAINT_WITHDRAWN, COMPLAINT_SOURCE_UPDATED
    }
    private final ContractNotificationOutboxRepository outbox;
    private final ComplaintAdminAudienceRepository admins;
    private final String topic;
    private final ObjectMapper json=new ObjectMapper();

    public ComplaintNotificationWriter(ContractNotificationOutboxRepository outbox,ComplaintAdminAudienceRepository admins,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox=outbox;this.admins=admins;this.topic=topic;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void changed(DisputeCase c,String actor,String role,String action,Map<String,Object> metadata) {
        if(c==null || blank(c.getId()) || blank(c.getRentalRequestId()) || !"RENTAL".equals(c.getSubjectType())
                || blank(c.getPayerId()) || blank(c.getPayeeId()) || c.getPayerId().equals(c.getPayeeId()))
            throw new IllegalStateException("Complaint notification needs the persisted rental case and distinct parties");
        boolean administrator="ADMIN".equals(role),system="SYSTEM".equals(role);
        if(administrator&&(Objects.equals(actor,c.getPayerId())||Objects.equals(actor,c.getPayeeId())))
            throw new IllegalStateException("A complaint party cannot notify as its administrator");
        if(!administrator&&!system&&!Objects.equals(actor,c.getPayerId())&&!Objects.equals(actor,c.getPayeeId()))
            throw new IllegalStateException("Complaint notification actor is not a stored party");
        switch(action) {
            case "OPENED" -> {
                other(c,actor,Event.COMPLAINT_OPENED);
                if(c.getStatus()==DisputeCaseStatus.ESCALATED)intake(c,Event.COMPLAINT_OPENED);
            }
            case "COMMENT" -> {
                if(administrator)both(c,Event.COMPLAINT_COMMENTED);
                else {other(c,actor,Event.COMPLAINT_COMMENTED);assigned(c,Event.COMPLAINT_COMMENTED);}
            }
            case "ESCALATE" -> {other(c,actor,Event.COMPLAINT_ESCALATED);intake(c,Event.COMPLAINT_ESCALATED);}
            case "CLAIM" -> {
                both(c,Event.COMPLAINT_ASSIGNED);
                String previous=Objects.toString(metadata.get("previousAssignee"),"");
                if(!previous.equals(actor))admin(c,previous,Event.COMPLAINT_ASSIGNED);
            }
            case "REQUEST_INFO" -> {
                String requested=Objects.toString(metadata.get("requestedFrom"),"");
                if(!Objects.equals(requested,c.getPayerId())&&!Objects.equals(requested,c.getPayeeId()))
                    throw new IllegalStateException("Complaint information recipient is not a stored party");
                write(c,requested,"PARTY",Event.COMPLAINT_INFO_REQUESTED);
            }
            case "DECIDE" -> both(c,c.getStatus()==DisputeCaseStatus.RESOLVED?Event.COMPLAINT_FINAL_DECISION:Event.COMPLAINT_DECIDED);
            case "CLOSE" -> other(c,actor,Event.COMPLAINT_CLOSED);
            case "WITHDRAW" -> other(c,actor,Event.COMPLAINT_WITHDRAWN);
            case "REOPEN" -> other(c,actor,Event.COMPLAINT_REOPENED);
            case "SOURCE_ATTACHED", "SOURCE_DISPUTED" -> {other(c,actor,Event.COMPLAINT_SOURCE_UPDATED);assigned(c,Event.COMPLAINT_SOURCE_UPDATED);}
            case "FINALIZED" -> both(c,Event.COMPLAINT_FINAL_DECISION);
            default -> throw new IllegalStateException("Unsupported complaint notification action");
        }
    }
    private void other(DisputeCase c,String actor,Event event) {
        if(!Objects.equals(actor,c.getPayerId())&&!Objects.equals(actor,c.getPayeeId()))throw new IllegalStateException("Complaint party actor missing");
        write(c,actor.equals(c.getPayerId())?c.getPayeeId():c.getPayerId(),"PARTY",event);
    }
    private void both(DisputeCase c,Event event) {
        write(c,c.getPayerId(),"PARTY",event);write(c,c.getPayeeId(),"PARTY",event);
    }
    private void assigned(DisputeCase c,Event event) { if(c.getStatus()==DisputeCaseStatus.ESCALATED)admin(c,c.getAssignedTo(),event); }
    private void admin(DisputeCase c,String recipient,Event event) {
        if(!blank(recipient)&&!recipient.equals(c.getPayerId())&&!recipient.equals(c.getPayeeId())&&admins.isActiveAdmin(recipient))
            write(c,recipient,"ADMIN",event);
    }
    private void intake(DisputeCase c,Event event) {
        String after=null;
        while(true) {
            var ids=admins.findIdsAfter(c.getPayerId(),c.getPayeeId(),after,PageRequest.of(0,100));
            if(ids.isEmpty())return;
            for(String id:ids)if(!id.equals(c.getPayerId())&&!id.equals(c.getPayeeId()))write(c,id,"ADMIN",event);
            after=ids.getLast();
        }
    }
    private void write(DisputeCase c,String recipient,String audience,Event event) {
        String id=UUID.randomUUID().toString();Instant now=Instant.now();
        Map<String,Object> envelope=new LinkedHashMap<>();
        envelope.put("eventId",id);envelope.put("eventType",event.name());envelope.put("version",1);
        envelope.put("occurredAt",new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(now));
        envelope.put("recipients",List.of(recipient));
        envelope.put("data",Map.of("caseId",c.getId(),"rentalRequestId",c.getRentalRequestId(),"audience",audience));
        try {outbox.save(new ContractNotificationOutbox(id,c.getId(),topic,json.writeValueAsString(envelope),now));}
        catch(JsonProcessingException e){throw new IllegalStateException("Cannot serialize complaint notification",e);}
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
}
