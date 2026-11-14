package com.hs.contract.notification;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.Contract;
import com.hs.contract.model.SignatureRequest;
import com.hs.contract.model.constant.SignerRole;
import com.hs.contract.repository.ContractRepository;
import com.hs.contract.repository.RentalWorkflowRecordRepository;
import com.hs.contract.repository.WorkflowSigningDocumentRepository;

/**
 * Phase 6A: enqueues v1 agreement-signing notifications for the persisted parties of the EXACT signing envelope
 * (the contract row that owns the signature requests; a child for amendment, renewal and move-in documents).
 * Recipients are the stored landlord/tenant of that envelope, additionally asserted equal to the root lease parties for
 * children, never a request or actor value. Every envelope has one recipient and data exactly
 * {agreementId, agreementKind}; no text, date, amount, signature or failure code enters Kafka. Aggregate/Kafka key =
 * agreementId, so ordering is only promised among events of the same envelope.
 */
@Service
public class AgreementSigningNotificationWriter {
    public enum Event { AGREEMENT_REVIEW_READY, AGREEMENT_SIGNING_TURN, AGREEMENT_FULLY_SIGNED, AGREEMENT_SIGNATURE_NEEDS_REVIEW }
    public enum Kind { LEASE, DEPOSIT, AMENDMENT, RENEWAL, MOVE_IN, EARLY_EXIT }

    private static final Logger log = LoggerFactory.getLogger(AgreementSigningNotificationWriter.class);
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final ContractNotificationOutboxRepository outbox;
    private final ContractRepository contracts;
    private final WorkflowSigningDocumentRepository workflowDocuments;
    private final RentalWorkflowRecordRepository workflowRecords;
    private final MoveInHandoverNotificationWriter handover;
    private final String topic;

    public AgreementSigningNotificationWriter(ContractNotificationOutboxRepository outbox, ContractRepository contracts,
            WorkflowSigningDocumentRepository workflowDocuments, RentalWorkflowRecordRepository workflowRecords,
            MoveInHandoverNotificationWriter handover,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.contracts = contracts;
        this.workflowDocuments = workflowDocuments;
        this.workflowRecords = workflowRecords;
        this.handover = handover;
        this.topic = topic;
    }

    /** The landlord may now review the document (system draft, first deposit PDF, tenant-prepared move-in PDF). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reviewReady(Contract envelope) {
        var kind = resolve(envelope);
        if (kind == null) return;
        write(envelope, Event.AGREEMENT_REVIEW_READY, kind, envelope.getLandlordId());
    }

    /** The tenant's turn to sign (landlord signature verified, deposit reopened, INTERNAL send). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void signingTurn(Contract envelope) {
        var kind = resolve(envelope);
        if (kind != null && !envelope.getTenantId().equals(envelope.getLandlordId()))
            write(envelope, Event.AGREEMENT_SIGNING_TURN, kind, envelope.getTenantId());
    }

    /** Every required signature on this envelope is verified: both parties, one envelope each. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fullySigned(Contract envelope) {
        var kind = resolve(envelope);
        if (kind == null) return;
        var recipients = new LinkedHashSet<String>();
        recipients.add(envelope.getLandlordId());
        recipients.add(envelope.getTenantId());
        for (String recipient : recipients) write(envelope, Event.AGREEMENT_FULLY_SIGNED, kind, recipient);
    }

    /**
     * Verified tenant signature activated the ROOT lease: the tenant gets FULLY_SIGNED and the landlord only the
     * move-in handover prompt. A lease whose parties coincide gets the actionable prompt only (no self-role duplicate).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void leaseActivated(Contract root) {
        if (resolve(root) != Kind.LEASE) throw new IllegalStateException("Lease activation notice needs the root lease");
        if (!root.getLandlordId().equals(root.getTenantId())) write(root, Event.AGREEMENT_FULLY_SIGNED, Kind.LEASE, root.getTenantId());
        handover.enqueueLandlord(root, MoveInHandoverNotificationWriter.Event.MOVE_IN_HANDOVER_READY);
    }

    /**
     * A signature that VNPT may already have given could not be completed/verified. Only the signer is told, and only
     * when the persisted party for the request's role is the request's signer; otherwise nothing is sent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void needsReview(SignatureRequest request, Contract envelope) {
        Kind kind;
        try {
            kind = resolve(envelope);
        } catch (IllegalStateException inconsistent) {
            // The request is already failing; an unresolvable envelope must not block that state, it just sends nothing.
            log.warn("SIGNATURE_NEEDS_REVIEW_ENVELOPE_INVALID request={}", request.getId());
            return;
        }
        if (kind == null) return;
        if (request.getSignerRole() == null) {
            log.warn("SIGNATURE_NEEDS_REVIEW_ROLE_MISSING request={}", request.getId());
            return;
        }
        String party = request.getSignerRole() == SignerRole.LANDLORD ? envelope.getLandlordId() : envelope.getTenantId();
        if (!Objects.equals(party, request.getSignerUserId())) {
            log.warn("SIGNATURE_NEEDS_REVIEW_SIGNER_MISMATCH request={} role={}", request.getId(), request.getSignerRole());
            return;
        }
        write(envelope, Event.AGREEMENT_SIGNATURE_NEEDS_REVIEW, kind, party);
    }

    /** Closed-enum kind from persisted data only. Fails on any inconsistent envelope. */
    private Kind resolve(Contract envelope) {
        if (envelope == null || envelope.getId() == null || envelope.getId().isBlank()
                || blank(envelope.getLandlordId()) || blank(envelope.getTenantId()))
            throw new IllegalStateException("Agreement notification envelope or parties missing");
        String parentId = envelope.getParentContractId();
        String stored = envelope.getAgreementKind();
        Kind kind;
        if ("DEPOSIT".equals(stored)) {
            kind = Kind.DEPOSIT;
            if (parentId != null) throw new IllegalStateException("Deposit agreement cannot be a child");
        } else if ("AMENDMENT".equals(stored)) {
            kind = Kind.AMENDMENT;
            if (parentId == null) throw new IllegalStateException("Amendment agreement needs a parent");
        } else if ("WORKFLOW".equals(stored)) {
            if (parentId == null) throw new IllegalStateException("Workflow agreement needs a parent");
            var document = workflowDocuments.findBySigningContractId(envelope.getId())
                    .orElseThrow(() -> new IllegalStateException("Workflow signing document missing"));
            var record = workflowRecords.findById(document.getWorkflowId())
                    .orElseThrow(() -> new IllegalStateException("Workflow record missing"));
            if (!"MOVE_IN".equals(record.getKind()) && !"EARLY_EXIT".equals(record.getKind()))
                throw new IllegalStateException("Unsupported workflow kind");
            kind = "MOVE_IN".equals(record.getKind()) ? Kind.MOVE_IN : Kind.EARLY_EXIT;
        } else if (stored == null) {
            kind = parentId == null ? Kind.LEASE : Kind.RENEWAL;
        } else {
            throw new IllegalStateException("Unsupported agreement kind");
        }
        if (parentId != null) {
            var parent = contracts.findById(parentId).orElseThrow(() -> new IllegalStateException("Agreement parent missing"));
            if (parent.getParentContractId() != null || !envelope.getLandlordId().equals(parent.getLandlordId())
                    || !envelope.getTenantId().equals(parent.getTenantId()))
                throw new IllegalStateException("Agreement parties do not match the root lease");
        }
        return kind;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private void write(Contract envelope, Event type, Kind kind, String recipient) {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agreementId", envelope.getId());
        data.put("agreementKind", kind.name());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventId", eventId);
        message.put("eventType", type.name());
        message.put("version", 1);
        message.put("occurredAt", TIMESTAMP.format(now));
        message.put("recipients", List.of(recipient));
        message.put("data", data);
        String payload;
        try {
            payload = json.writeValueAsString(message);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Agreement notification serialization failed", failure);
        }
        outbox.save(new ContractNotificationOutbox(eventId, envelope.getId(), topic, payload, now));
    }
}
