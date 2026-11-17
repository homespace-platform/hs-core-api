package com.hs.contract.notification;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationalIncidentServiceTest {
    @Test void outageAndRecoveryNotifyOncePerTransitionAndNeverIncludePrivateDetails() throws Exception {
        var repo=mock(OperationalIncidentRepository.class);var admins=mock(ComplaintAdminAudienceRepository.class);
        var outbox=mock(ContractNotificationOutboxRepository.class);
        var service=new OperationalIncidentService(repo,admins,outbox);ReflectionTestUtils.setField(service,"topic","events");
        var incident=new OperationalIncident();incident.setId("incident-1");incident.setComponent("GATEWAY");incident.setSourceKey("gateway");
        when(repo.lock("gateway")).thenReturn(Optional.of(incident));
        when(admins.findIdsAfter(isNull(),isNull(),isNull(),any())).thenReturn(List.of("admin"));
        service.observe("gateway","GATEWAY",null,true);service.observe("gateway","GATEWAY",null,true);
        assertTrue(incident.isOpen());service.observe("gateway","GATEWAY",null,false);service.observe("gateway","GATEWAY",null,false);
        assertFalse(incident.isOpen());assertNotNull(incident.getResolvedAt());
        var captured=org.mockito.ArgumentCaptor.forClass(ContractNotificationOutbox.class);verify(outbox,times(2)).save(captured.capture());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(List.of("SYSTEM_INCIDENT_OPENED","SYSTEM_INCIDENT_RESOLVED"),captured.getAllValues().stream().map(row->{try{return json.readTree(row.getPayload()).get("eventType").asText();}catch(Exception e){throw new AssertionError(e);}}).toList());
        for(var row:captured.getAllValues())assertEquals(Set.of("incidentId","component"),json.convertValue(json.readTree(row.getPayload()).get("data"),Map.class).keySet());
    }
    @Test void aHealthySystemWithNoIncidentDoesNotInventARecoveryNotification(){
        var repo=mock(OperationalIncidentRepository.class);var admins=mock(ComplaintAdminAudienceRepository.class);var outbox=mock(ContractNotificationOutboxRepository.class);
        var service=new OperationalIncidentService(repo,admins,outbox);
        service.observe("gateway","GATEWAY",null,false);
        verify(repo,never()).initialize(any(),any(),any(),any());verifyNoInteractions(admins,outbox);
        assertThrows(IllegalArgumentException.class,()->service.observe("bad","PRIVATE_URL",null,true));
    }
}
