package com.hs.contract.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

@Service @RequiredArgsConstructor
public class OperationalIncidentService {
    private final OperationalIncidentRepository incidents;
    private final ComplaintAdminAudienceRepository admins;
    private final ContractNotificationOutboxRepository outbox;
    @Value("${notification.events-topic}") private String topic;
    private final ObjectMapper json=new ObjectMapper();

    @Transactional
    public void observe(String sourceKey,String component,String resourceId,boolean failing) {
        if(!Set.of("BLOCKCHAIN","GATEWAY").contains(component)||sourceKey==null||sourceKey.length()>200)throw new IllegalArgumentException("Invalid incident identity");
        if(failing)incidents.initialize(UUID.randomUUID().toString(),sourceKey,component,resourceId);
        var incident=incidents.lock(sourceKey).orElse(null);
        if(incident==null||incident.isOpen()==failing)return;
        Instant now=Instant.now();incident.setOpen(failing);
        if(failing){incident.setOpenedAt(now);incident.setResolvedAt(null);}else incident.setResolvedAt(now);
        incidents.save(incident);
        String after=null;
        while(true){
            var recipients=admins.findIdsAfter(null,null,after,PageRequest.of(0,100));
            if(recipients.isEmpty())break;
            for(String recipient:recipients){
                String eventId=UUID.randomUUID().toString();
                var payload=Map.of("eventId",eventId,"eventType",failing?"SYSTEM_INCIDENT_OPENED":"SYSTEM_INCIDENT_RESOLVED",
                    "version",1,"occurredAt",new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(now),
                    "recipients",List.of(recipient),"data",Map.of("incidentId",incident.getId(),"component",component));
                try{outbox.save(new ContractNotificationOutbox(eventId,incident.getId(),topic,json.writeValueAsString(payload),now));}
                catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Incident notification serialization failed",invalid);}
            }
            after=recipients.getLast();
        }
    }

    @Transactional(readOnly=true)
    public List<OperationalIncident> list(){return incidents.findByOrderByOpenedAtDesc(PageRequest.of(0,100));}
}
