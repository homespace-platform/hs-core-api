package com.hs.contract.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.Contract;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerminationNotificationWriterTest {
    private final ContractNotificationOutboxRepository outbox = mock(ContractNotificationOutboxRepository.class);
    private final List<ContractNotificationOutbox> rows = new ArrayList<>();
    private final TerminationNotificationWriter writer = new TerminationNotificationWriter(outbox, "events");
    private final Contract root = Contract.builder().id("root").landlordId("owner").tenantId("tenant").build();
    private final ObjectMapper json = new ObjectMapper();

    TerminationNotificationWriterTest() {
        when(outbox.save(any())).thenAnswer(call -> { var row = (ContractNotificationOutbox) call.getArgument(0); rows.add(row); return row; });
    }

    @Test void targetsOnlyPersistedOtherPartyAndMinimalRootIdentity() throws Exception {
        writer.other(root, "tenant", TerminationNotificationWriter.Event.TERMINATION_RESPONSE_NEEDED);
        var event = json.readTree(rows.getFirst().getPayload());
        assertEquals("root", rows.getFirst().getAggregateId());
        assertEquals("owner", event.get("recipients").get(0).asText());
        assertEquals(1, event.get("recipients").size());
        assertEquals("root", event.get("data").get("contractId").asText());
        assertEquals(1, event.get("data").size());
        assertEquals(6, event.size());
    }

    @Test void bothTargetsDistinctPartiesAndRejectsUntrustedIdentity() {
        writer.both(root, TerminationNotificationWriter.Event.TERMINATION_ENDED);
        assertEquals(2, rows.size());
        root.setTenantId("owner");
        writer.both(root, TerminationNotificationWriter.Event.LEASE_EXPIRED);
        assertEquals(3, rows.size());
        assertThrows(IllegalStateException.class, () -> writer.other(root, "stranger", TerminationNotificationWriter.Event.TERMINATION_AGREED));
        root.setParentContractId("parent");
        assertThrows(IllegalStateException.class, () -> writer.both(root, TerminationNotificationWriter.Event.LEASE_EXPIRED));
    }

    @Test void exportsActualJavaEnvelopesForConsumer() throws Exception {
        for (var type : java.util.EnumSet.range(TerminationNotificationWriter.Event.TERMINATION_RESPONSE_NEEDED,
                TerminationNotificationWriter.Event.LEASE_EXPIRED)) writer.other(root, "tenant", type);
        assertEquals(10, rows.size());
        String output = System.getenv("HS_TERMINATION_FIXTURE_OUT");
        if (output != null && !output.isBlank())
            Files.write(Path.of(output), rows.stream().map(ContractNotificationOutbox::getPayload).toList());
    }

    @Test void exportsReturnSettlementEnvelopesAndValidatesStoredRecipients() throws Exception {
        var types=java.util.EnumSet.range(TerminationNotificationWriter.Event.LEASE_RETURN_CONFIRMED,TerminationNotificationWriter.Event.LEASE_CLOSED);
        for(var type:types)writer.party(root,"tenant",type);
        assertEquals(9,rows.size());
        for(var row:rows){var event=json.readTree(row.getPayload());
            assertEquals(6,event.size());assertEquals(1,event.get("version").asInt());
            assertEquals("tenant",event.get("recipients").get(0).asText());assertEquals(1,event.get("recipients").size());
            assertEquals("root",event.get("data").get("contractId").asText());assertEquals(1,event.get("data").size());
            assertEquals("root",row.getAggregateId());assertEquals(row.getEventId(),event.get("eventId").asText());}
        assertEquals(9,rows.stream().map(ContractNotificationOutbox::getEventId).distinct().count());
        assertThrows(IllegalStateException.class,()->writer.party(root,"intruder",TerminationNotificationWriter.Event.SETTLEMENT_PAYMENT_OVERDUE));
        String output=System.getenv("HS_RETURN_SETTLEMENT_FIXTURE_OUT");
        if(output!=null&&!output.isBlank())Files.write(Path.of(output),rows.stream().map(ContractNotificationOutbox::getPayload).toList());
    }
}
