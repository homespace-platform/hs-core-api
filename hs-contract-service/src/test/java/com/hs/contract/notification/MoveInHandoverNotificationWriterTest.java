package com.hs.contract.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.Contract;
import com.hs.contract.notification.MoveInHandoverNotificationWriter.Event;

class MoveInHandoverNotificationWriterTest {
    static final ObjectMapper JSON = new ObjectMapper();
    final ContractNotificationOutboxRepository repository = mock(ContractNotificationOutboxRepository.class);
    final List<ContractNotificationOutbox> rows = new ArrayList<>();
    final MoveInHandoverNotificationWriter writer = new MoveInHandoverNotificationWriter(repository, "homespace.notification.events.v1");
    final Contract contract = Contract.builder().id("contract").landlordId("landlord").tenantId("tenant").build();

    MoveInHandoverNotificationWriterTest() {
        when(repository.save(any(ContractNotificationOutbox.class))).thenAnswer(call -> { rows.add(call.getArgument(0)); return call.getArgument(0); });
    }

    List<String> keys() throws Exception {
        var result = new ArrayList<String>();
        for (var row : rows) { JsonNode e = JSON.readTree(row.getPayload()); result.add(e.get("eventType").asText() + ":" + e.get("recipients").get(0).asText()); }
        return result;
    }

    @Test void fiveEventsExactlyAndEveryEnqueueMethodRequiresAnExistingTransaction() {
        assertEquals(List.of("MOVE_IN_HANDOVER_PROPOSED", "MOVE_IN_HANDOVER_CONFIRMED", "MOVE_IN_HANDOVER_COMPLETED", "MOVE_IN_HANDOVER_DISPUTED", "MOVE_IN_HANDOVER_READY"),
                Stream.of(Event.values()).map(Enum::name).toList());
        var enqueue = Stream.of(MoveInHandoverNotificationWriter.class.getDeclaredMethods()).filter(m -> m.getName().startsWith("enqueue")).toList();
        assertEquals(3, enqueue.size());
        assertTrue(enqueue.stream().map(m -> m.getAnnotation(Transactional.class)).allMatch(t -> t != null && t.propagation() == Propagation.MANDATORY));
    }

    @Test void otherPartyIsDerivedFromTheStoredPartiesInBothDirections() throws Exception {
        writer.enqueueOther(contract, "landlord", Event.MOVE_IN_HANDOVER_PROPOSED);
        writer.enqueueOther(contract, "tenant", Event.MOVE_IN_HANDOVER_CONFIRMED);
        assertEquals(List.of("MOVE_IN_HANDOVER_PROPOSED:tenant", "MOVE_IN_HANDOVER_CONFIRMED:landlord"), keys());
    }

    @Test void bothWritesLandlordThenTenantAndCoincidentPartiesProduceOneEnvelope() throws Exception {
        writer.enqueueBoth(contract, Event.MOVE_IN_HANDOVER_COMPLETED);
        assertEquals(List.of("MOVE_IN_HANDOVER_COMPLETED:landlord", "MOVE_IN_HANDOVER_COMPLETED:tenant"), keys());
        rows.clear();
        contract.setTenantId("landlord");
        writer.enqueueBoth(contract, Event.MOVE_IN_HANDOVER_COMPLETED);
        assertEquals(1, rows.size());
    }

    @Test void selfOwnedLeaseNeverNotifiesTheActor() {
        contract.setTenantId("landlord");
        writer.enqueueOther(contract, "landlord", Event.MOVE_IN_HANDOVER_DISPUTED);
        assertEquals(0, rows.size());
    }

    @Test void strangerActorSigningChildAndIncompleteIdentityAreRejectedBeforeAnyWrite() {
        assertThrows(IllegalStateException.class, () -> writer.enqueueOther(contract, "stranger", Event.MOVE_IN_HANDOVER_PROPOSED));
        assertThrows(IllegalStateException.class, () -> writer.enqueueOther(contract, null, Event.MOVE_IN_HANDOVER_PROPOSED));
        var child = Contract.builder().id("bb").parentContractId("contract").landlordId("landlord").tenantId("tenant").build();
        assertThrows(IllegalStateException.class, () -> writer.enqueueBoth(child, Event.MOVE_IN_HANDOVER_COMPLETED));
        assertThrows(IllegalStateException.class, () -> writer.enqueueBoth(Contract.builder().id("contract").landlordId("landlord").build(), Event.MOVE_IN_HANDOVER_COMPLETED));
        assertThrows(IllegalStateException.class, () -> writer.enqueueBoth(Contract.builder().landlordId("landlord").tenantId("tenant").build(), Event.MOVE_IN_HANDOVER_COMPLETED));
        assertThrows(IllegalStateException.class, () -> writer.enqueueOther(null, "landlord", Event.MOVE_IN_HANDOVER_PROPOSED));
        assertEquals(0, rows.size());
    }

    @Test void envelopeIsTheStrictV1ShapeWithOnlyTheRootContractId() throws Exception {
        writer.enqueueOther(contract, "landlord", Event.MOVE_IN_HANDOVER_PROPOSED);
        var row = rows.getFirst();
        JsonNode e = JSON.readTree(row.getPayload());
        assertEquals(List.of("eventId", "eventType", "version", "occurredAt", "recipients", "data"), List.copyOf(fields(e)));
        assertEquals(List.of("contractId"), List.copyOf(fields(e.get("data"))));
        assertEquals(1, e.get("version").asInt());
        assertEquals(1, e.get("recipients").size());
        assertEquals("contract", e.get("data").get("contractId").asText());
        assertEquals(row.getEventId(), e.get("eventId").asText());
        assertEquals("contract", row.getAggregateId());
        assertEquals("homespace.notification.events.v1", row.getTopic());
        assertNull(row.getSentAt());
        assertTrue(e.get("occurredAt").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    static List<String> fields(JsonNode node) { var names = new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add); return names; }

    /** Real writer output for the Node consumer cross-check; written only when HS_HANDOVER_FIXTURE_OUT names a file. */
    @Test void javaEnvelopesForConsumerCrossCheck() throws Exception {
        var live = Contract.builder().id(java.util.UUID.randomUUID().toString()).landlordId("landlord-" + java.util.UUID.randomUUID())
                .tenantId("tenant-" + java.util.UUID.randomUUID()).build();
        writer.enqueueOther(live, live.getLandlordId(), Event.MOVE_IN_HANDOVER_PROPOSED);
        writer.enqueueOther(live, live.getTenantId(), Event.MOVE_IN_HANDOVER_CONFIRMED);
        writer.enqueueBoth(live, Event.MOVE_IN_HANDOVER_COMPLETED);
        writer.enqueueOther(live, live.getTenantId(), Event.MOVE_IN_HANDOVER_DISPUTED);
        assertEquals(5, rows.size());
        assertEquals(4, keys().stream().map(k -> k.substring(0, k.indexOf(':'))).distinct().count());
        String out = System.getenv("HS_HANDOVER_FIXTURE_OUT");
        if (out != null && !out.isBlank())
            java.nio.file.Files.write(java.nio.file.Path.of(out), rows.stream().map(ContractNotificationOutbox::getPayload).toList());
    }
}
