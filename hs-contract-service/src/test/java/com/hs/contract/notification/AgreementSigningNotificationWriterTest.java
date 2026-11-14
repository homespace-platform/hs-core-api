package com.hs.contract.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.Contract;
import com.hs.contract.model.RentalWorkflowRecord;
import com.hs.contract.model.SignatureRequest;
import com.hs.contract.model.WorkflowSigningDocument;
import com.hs.contract.model.constant.SignerRole;
import com.hs.contract.repository.ContractRepository;
import com.hs.contract.repository.RentalWorkflowRecordRepository;
import com.hs.contract.repository.WorkflowSigningDocumentRepository;

class AgreementSigningNotificationWriterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContractNotificationOutboxRepository outbox = mock(ContractNotificationOutboxRepository.class);
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final WorkflowSigningDocumentRepository documents = mock(WorkflowSigningDocumentRepository.class);
    private final RentalWorkflowRecordRepository workflows = mock(RentalWorkflowRecordRepository.class);
    private final MoveInHandoverNotificationWriter handover = mock(MoveInHandoverNotificationWriter.class);
    private final AgreementSigningNotificationWriter writer = new AgreementSigningNotificationWriter(
            outbox, contracts, documents, workflows, handover, "homespace.notification.events.v1");
    private final List<ContractNotificationOutbox> rows = new ArrayList<>();
    private final Contract root = contract("root", null, null);

    AgreementSigningNotificationWriterTest() {
        when(outbox.save(any())).thenAnswer(call -> { var row = (ContractNotificationOutbox) call.getArgument(0); rows.add(row); return row; });
        when(contracts.findById("root")).thenReturn(Optional.of(root));
    }

    private Contract contract(String id, String parentId, String kind) {
        return Contract.builder().id(id).parentContractId(parentId).agreementKind(kind)
                .landlordId("landlord").tenantId("tenant").build();
    }

    private Contract workflow(String id, String kind) {
        var contract = contract(id, "root", "WORKFLOW");
        var document = new WorkflowSigningDocument();
        document.setSigningContractId(id);
        document.setWorkflowId("workflow-" + id);
        var record = new RentalWorkflowRecord();
        record.setId(document.getWorkflowId());
        record.setKind(kind);
        when(documents.findBySigningContractId(id)).thenReturn(Optional.of(document));
        when(workflows.findById(document.getWorkflowId())).thenReturn(Optional.of(record));
        return contract;
    }

    private JsonNode event(int i) throws Exception { return JSON.readTree(rows.get(i).getPayload()); }

    @Test void allKindsUseTheExactSigningEnvelopeAndOneStoredRecipient() throws Exception {
        for (var contract : List.of(root, contract("deposit", null, "DEPOSIT"),
                contract("amendment", "root", "AMENDMENT"), contract("renewal", "root", null), workflow("move-in", "MOVE_IN"), workflow("early-exit", "EARLY_EXIT"))) {
            writer.signingTurn(contract);
            JsonNode event = event(rows.size() - 1);
            assertEquals("AGREEMENT_SIGNING_TURN", event.get("eventType").asText());
            assertEquals(contract.getId(), event.get("data").get("agreementId").asText());
            assertEquals("tenant", event.get("recipients").get(0).asText());
            assertEquals(1, event.get("recipients").size());
            assertEquals(contract.getId(), rows.getLast().getAggregateId());
        }
        assertEquals(List.of("LEASE", "DEPOSIT", "AMENDMENT", "RENEWAL", "MOVE_IN", "EARLY_EXIT"),
                rows.stream().map(row -> {
                    try { return JSON.readTree(row.getPayload()).get("data").get("agreementKind").asText(); }
                    catch (Exception e) { throw new AssertionError(e); }
                }).toList());
        assertEquals(List.of("eventId", "eventType", "version", "occurredAt", "recipients", "data"), fields(event(0)));
        assertEquals(List.of("agreementId", "agreementKind"), fields(event(0).get("data")));
        assertEquals(1, event(0).get("version").asInt());
    }

    @Test void completionAndLeaseActivationHaveTheFrozenRecipients() throws Exception {
        writer.fullySigned(contract("deposit", null, "DEPOSIT"));
        assertEquals("landlord", event(0).get("recipients").get(0).asText());
        assertEquals("tenant", event(1).get("recipients").get(0).asText());
        rows.clear();
        writer.leaseActivated(root);
        assertEquals(1, rows.size());
        assertEquals("tenant", event(0).get("recipients").get(0).asText());
        verify(handover).enqueueLandlord(root, MoveInHandoverNotificationWriter.Event.MOVE_IN_HANDOVER_READY);
    }

    @Test void failureNoticeGoesOnlyToTheVerifiedPersistedSigner() throws Exception {
        var request = SignatureRequest.builder().id("request").signerRole(SignerRole.LANDLORD).signerUserId("landlord").build();
        writer.needsReview(request, root);
        assertEquals("AGREEMENT_SIGNATURE_NEEDS_REVIEW", event(0).get("eventType").asText());
        assertEquals("landlord", event(0).get("recipients").get(0).asText());
        request.setSignerUserId("stranger");
        writer.needsReview(request, root);
        request.setSignerRole(null);
        writer.needsReview(request, root);
        assertEquals(1, rows.size());
    }

    @Test void invalidChildrenCannotLeakAndEarlyExitUsesTheSameSigningContract() throws Exception {
        var mismatched = contract("bad", "root", "AMENDMENT");
        mismatched.setTenantId("other");
        assertThrows(IllegalStateException.class, () -> writer.signingTurn(mismatched));
        writer.reviewReady(contract("amendment", "root", "AMENDMENT"));
        writer.reviewReady(contract("renewal", "root", null));
        var earlyExit = workflow("early-exit", "EARLY_EXIT");
        writer.signingTurn(earlyExit);
        writer.fullySigned(earlyExit);
        writer.needsReview(SignatureRequest.builder().id("request").signerRole(SignerRole.TENANT)
                .signerUserId("tenant").build(), earlyExit);
        assertEquals(6, rows.size());
        assertEquals("EARLY_EXIT", event(2).get("data").get("agreementKind").asText());
    }

    @Test void javaEnvelopesForConsumerCrossCheck() throws Exception {
        var id = UUID.randomUUID().toString();
        var lease = contract(id, null, null);
        writer.reviewReady(lease);
        writer.signingTurn(lease);
        writer.fullySigned(lease);
        writer.needsReview(SignatureRequest.builder().id(UUID.randomUUID().toString())
                .signerRole(SignerRole.TENANT).signerUserId("tenant").build(), lease);
        writer.reviewReady(contract(UUID.randomUUID().toString(), null, "DEPOSIT"));
        writer.reviewReady(workflow(UUID.randomUUID().toString(), "MOVE_IN"));
        writer.reviewReady(contract(UUID.randomUUID().toString(), "root", "AMENDMENT"));
        writer.reviewReady(contract(UUID.randomUUID().toString(), "root", null));
        writer.reviewReady(workflow(UUID.randomUUID().toString(), "EARLY_EXIT"));
        for (var child : List.of(contract(UUID.randomUUID().toString(), "root", "AMENDMENT"),
                contract(UUID.randomUUID().toString(), "root", null))) writer.signingTurn(child);
        assertEquals(12, rows.size());
        String output = System.getenv("HS_AGREEMENT_FIXTURE_OUT");
        if (output != null && !output.isBlank())
            Files.write(Path.of(output), rows.stream().map(ContractNotificationOutbox::getPayload).toList());
    }

    private static List<String> fields(JsonNode node) {
        var result = new ArrayList<String>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }
}
