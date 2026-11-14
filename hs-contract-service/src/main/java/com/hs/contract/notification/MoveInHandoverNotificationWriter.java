package com.hs.contract.notification;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.Contract;

/**
 * Enqueues v1 move-in handover notifications for the persisted parties of the locked ROOT lease contract (never a
 * signing envelope). Callers decide which committed transition produces an event; recipients are the stored
 * landlord/tenant, never request values. Each envelope has one recipient and data exactly {contractId}: no date,
 * meter, key, inventory, reason, name, actor, role or status enters Kafka. Kafka key / outbox aggregate = contractId.
 */
@Service
public class MoveInHandoverNotificationWriter {
    public enum Event { MOVE_IN_HANDOVER_PROPOSED, MOVE_IN_HANDOVER_CONFIRMED, MOVE_IN_HANDOVER_COMPLETED, MOVE_IN_HANDOVER_DISPUTED,
        /** Phase 6A: lease just became ACTIVE; landlord only, no proposal exists yet. */ MOVE_IN_HANDOVER_READY }

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final ContractNotificationOutboxRepository outbox;
    private final String topic;

    public MoveInHandoverNotificationWriter(ContractNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    /** The party that is not the actor; nobody when both roles are the same person (no self-notification). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueOther(Contract contract, String actorId, Event type) {
        validate(contract);
        if (!contract.getLandlordId().equals(actorId) && !contract.getTenantId().equals(actorId))
            throw new IllegalStateException("Handover notification actor is not a stored party");
        String other = contract.getLandlordId().equals(actorId) ? contract.getTenantId() : contract.getLandlordId();
        if (!other.equals(actorId)) write(contract, type, other);
    }

    /** Landlord only (the lease-activation prompt); never the tenant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueLandlord(Contract contract, Event type) {
        validate(contract);
        write(contract, type, contract.getLandlordId());
    }

    /** Landlord then tenant, one envelope each; a contract whose parties coincide produces a single envelope. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueBoth(Contract contract, Event type) {
        validate(contract);
        var recipients = new LinkedHashSet<String>();
        recipients.add(contract.getLandlordId());
        recipients.add(contract.getTenantId());
        for (String recipient : recipients) write(contract, type, recipient);
    }

    private static void validate(Contract contract) {
        if (contract == null || contract.getId() == null || contract.getId().isBlank())
            throw new IllegalStateException("Handover notification contract missing");
        if (contract.getParentContractId() != null)
            throw new IllegalStateException("Handover notification needs the root lease contract, not a signing child");
        if (contract.getLandlordId() == null || contract.getLandlordId().isBlank()
                || contract.getTenantId() == null || contract.getTenantId().isBlank())
            throw new IllegalStateException("Handover notification parties missing");
    }

    private void write(Contract contract, Event type, String recipient) {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type.name());
        envelope.put("version", 1);
        envelope.put("occurredAt", TIMESTAMP.format(now));
        envelope.put("recipients", List.of(recipient));
        envelope.put("data", Map.of("contractId", contract.getId()));
        String payload;
        try {
            payload = json.writeValueAsString(envelope);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Handover notification serialization failed", failure);
        }
        outbox.save(new ContractNotificationOutbox(eventId, contract.getId(), topic, payload, now));
    }
}
