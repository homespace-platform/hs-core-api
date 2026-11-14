package com.hs.contract.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.Contract;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AmendmentNotificationWriterTest {
    private final ContractNotificationOutboxRepository outbox = mock(ContractNotificationOutboxRepository.class);
    private final List<ContractNotificationOutbox> rows = new ArrayList<>();
    private final AmendmentNotificationWriter writer = new AmendmentNotificationWriter(outbox, "events");
    private final Contract root = Contract.builder().id("root").landlordId("owner").tenantId("tenant").build();
    private final ObjectMapper json = new ObjectMapper();

    AmendmentNotificationWriterTest() {
        when(outbox.save(any())).thenAnswer(call -> { var row = (ContractNotificationOutbox) call.getArgument(0); rows.add(row); return row; });
    }

    @Test void writesOnlyTheOtherPersistedPartyWithTheRootId() throws Exception {
        writer.other(root, "owner", AmendmentNotificationWriter.Event.AMENDMENT_RESPONSE_NEEDED);
        var event = json.readTree(rows.getFirst().getPayload());
        assertEquals("root", rows.getFirst().getAggregateId());
        assertEquals(List.of("tenant"), json.readValue(event.get("recipients").toString(), List.class));
        assertEquals("root", event.get("data").get("contractId").asText());
        assertEquals(1, event.get("data").size());
        assertEquals(6, event.size());
        assertEquals("events", rows.getFirst().getTopic());
    }

    @Test void bothWritesSingletonEnvelopesAndDeduplicatesCoincidentParties() throws Exception {
        writer.both(root, AmendmentNotificationWriter.Event.AMENDMENT_APPLIED);
        assertEquals(2, rows.size());
        assertEquals("owner", json.readTree(rows.get(0).getPayload()).get("recipients").get(0).asText());
        assertEquals("tenant", json.readTree(rows.get(1).getPayload()).get("recipients").get(0).asText());
        root.setTenantId("owner");
        writer.both(root, AmendmentNotificationWriter.Event.AMENDMENT_EXPIRED);
        assertEquals(3, rows.size());
        writer.other(root, "owner", AmendmentNotificationWriter.Event.AMENDMENT_RESPONSE_NEEDED);
        assertEquals(3, rows.size());
    }

    @Test void rejectsChildMissingPartiesAndUntrustedActor() {
        assertThrows(IllegalStateException.class, () -> writer.other(root, "stranger", AmendmentNotificationWriter.Event.AMENDMENT_DECIDED));
        assertThrows(IllegalStateException.class, () -> writer.other(root, null, AmendmentNotificationWriter.Event.AMENDMENT_DECIDED));
        root.setParentContractId("parent");
        assertThrows(IllegalStateException.class, () -> writer.both(root, AmendmentNotificationWriter.Event.AMENDMENT_APPLIED));
        root.setParentContractId(null);
        root.setTenantId(null);
        assertThrows(IllegalStateException.class, () -> writer.both(root, AmendmentNotificationWriter.Event.AMENDMENT_APPLIED));
        assertTrue(rows.isEmpty());
    }

    @Test void exposesMandatoryTransactionalContractAndExportsActualEvents() throws Exception {
        assertEquals(Propagation.MANDATORY, AmendmentNotificationWriter.class.getMethod("other", Contract.class, String.class,
                AmendmentNotificationWriter.Event.class).getAnnotation(Transactional.class).propagation());
        for (var type : AmendmentNotificationWriter.Event.values()) writer.other(root, "owner", type);
        assertEquals(5, rows.size());
        String output = System.getenv("HS_AMENDMENT_FIXTURE_OUT");
        if (output != null && !output.isBlank())
            Files.write(Path.of(output), rows.stream().map(ContractNotificationOutbox::getPayload).toList());
    }
}
