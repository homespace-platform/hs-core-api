package com.hs.contract.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Unit-test fixture: the real writer over a recording repository. No database. */
public final class InvoiceOutboxProbe {
    public record Sent(String type, String recipient, String contractId, String invoiceId, String eventId, JsonNode envelope) {
        public String key() { return type + ":" + recipient + ":" + invoiceId; }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    public final ContractNotificationOutboxRepository repository = mock(ContractNotificationOutboxRepository.class);
    public final InvoiceNotificationWriter writer = new InvoiceNotificationWriter(repository, "homespace.notification.events.v1");
    private final List<ContractNotificationOutbox> rows = new ArrayList<>();

    public InvoiceOutboxProbe() {
        when(repository.save(any(ContractNotificationOutbox.class))).thenAnswer(call -> {
            rows.add(call.getArgument(0));
            return call.getArgument(0);
        });
    }

    public List<Sent> sent() {
        return rows.stream().map(row -> {
            try {
                JsonNode envelope = JSON.readTree(row.getPayload());
                return new Sent(envelope.get("eventType").asText(), envelope.get("recipients").get(0).asText(),
                        envelope.get("data").get("contractId").asText(), envelope.get("data").get("invoiceId").asText(),
                        envelope.get("eventId").asText(), envelope);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).toList();
    }

    public List<String> keys() { return sent().stream().map(Sent::key).toList(); }
    public List<ContractNotificationOutbox> rows() { return rows; }
    public void clear() { rows.clear(); }
}
