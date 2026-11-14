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

class RenewalNotificationWriterTest {
    private final ContractNotificationOutboxRepository outbox = mock(ContractNotificationOutboxRepository.class);
    private final List<ContractNotificationOutbox> rows = new ArrayList<>();
    private final RenewalNotificationWriter writer = new RenewalNotificationWriter(outbox, "events");
    private final Contract root = Contract.builder().id("root").landlordId("owner").tenantId("tenant").build();
    private final ObjectMapper json = new ObjectMapper();

    RenewalNotificationWriterTest() {
        when(outbox.save(any())).thenAnswer(call -> { var row = (ContractNotificationOutbox) call.getArgument(0); rows.add(row); return row; });
    }

    @Test void sendsOnlyPersistedOtherPartyWithMinimalRootIdentity() throws Exception {
        writer.other(root, "tenant", RenewalNotificationWriter.Event.RENEWAL_REQUESTED);
        var event = json.readTree(rows.getFirst().getPayload());
        assertEquals("root", rows.getFirst().getAggregateId());
        assertEquals("owner", event.get("recipients").get(0).asText());
        assertEquals(1, event.get("recipients").size());
        assertEquals("root", event.get("data").get("contractId").asText());
        assertEquals(1, event.get("data").size());
        assertEquals(6, event.size());
    }

    @Test void expirationNotifiesEachDistinctPartyOnce() throws Exception {
        writer.both(root, RenewalNotificationWriter.Event.RENEWAL_EXPIRED);
        assertEquals(2, rows.size());
        root.setTenantId("owner");
        writer.both(root, RenewalNotificationWriter.Event.RENEWAL_EXPIRED);
        writer.other(root, "owner", RenewalNotificationWriter.Event.RENEWAL_REQUESTED);
        assertEquals(3, rows.size());
    }

    @Test void rejectsChildMissingPartyAndUntrustedActor() {
        assertThrows(IllegalStateException.class, () -> writer.other(root, "stranger", RenewalNotificationWriter.Event.RENEWAL_APPROVED));
        assertThrows(IllegalStateException.class, () -> writer.other(root, null, RenewalNotificationWriter.Event.RENEWAL_APPROVED));
        root.setParentContractId("parent");
        assertThrows(IllegalStateException.class, () -> writer.both(root, RenewalNotificationWriter.Event.RENEWAL_EXPIRED));
        root.setParentContractId(null); root.setTenantId(null);
        assertThrows(IllegalStateException.class, () -> writer.both(root, RenewalNotificationWriter.Event.RENEWAL_EXPIRED));
        assertTrue(rows.isEmpty());
    }

    @Test void exportsActualJavaEnvelopesForTheCompiledConsumer() throws Exception {
        for (var type : RenewalNotificationWriter.Event.values()) writer.other(root, "tenant", type);
        assertEquals(4, rows.size());
        String output = System.getenv("HS_RENEWAL_FIXTURE_OUT");
        if (output != null && !output.isBlank())
            Files.write(Path.of(output), rows.stream().map(ContractNotificationOutbox::getPayload).toList());
    }
}
