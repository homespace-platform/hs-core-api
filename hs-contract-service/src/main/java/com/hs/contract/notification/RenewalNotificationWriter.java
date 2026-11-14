package com.hs.contract.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.Contract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RenewalNotificationWriter {
    public enum Event { RENEWAL_REQUESTED, RENEWAL_APPROVED, RENEWAL_REJECTED, RENEWAL_EXPIRED }
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ContractNotificationOutboxRepository outbox;
    private final String topic;
    private final ObjectMapper json = new ObjectMapper();

    public RenewalNotificationWriter(ContractNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void other(Contract root, String actor, Event event) {
        validate(root);
        if (!Objects.equals(actor, root.getLandlordId()) && !Objects.equals(actor, root.getTenantId()))
            throw new IllegalStateException("Renewal notice actor is not a stored party");
        String recipient = actor.equals(root.getLandlordId()) ? root.getTenantId() : root.getLandlordId();
        if (!recipient.equals(actor)) write(root, recipient, event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void both(Contract root, Event event) {
        validate(root);
        for (String recipient : new LinkedHashSet<>(List.of(root.getLandlordId(), root.getTenantId())))
            write(root, recipient, event);
    }

    private static void validate(Contract root) {
        if (root == null || root.getId() == null || root.getId().isBlank() || root.getParentContractId() != null
                || root.getLandlordId() == null || root.getLandlordId().isBlank()
                || root.getTenantId() == null || root.getTenantId().isBlank())
            throw new IllegalStateException("Renewal notice needs a root lease and its stored parties");
    }

    private void write(Contract root, String recipient, Event event) {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", event.name());
        payload.put("version", 1);
        payload.put("occurredAt", TIMESTAMP.format(now));
        payload.put("recipients", List.of(recipient));
        payload.put("data", Map.of("contractId", root.getId()));
        try {
            outbox.save(new ContractNotificationOutbox(eventId, root.getId(), topic, json.writeValueAsString(payload), now));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Renewal notice serialization failed", failure);
        }
    }
}
