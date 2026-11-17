package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.event.WorkflowDocumentSigned;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.notification.AgreementSigningNotificationWriter;
import com.hs.contract.repository.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.repository.DisputeCaseRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowDocumentServiceTest {
    final RentalWorkflowRecordRepository workflows = mock(RentalWorkflowRecordRepository.class);
    final WorkflowSigningDocumentRepository signingDocuments = mock(WorkflowSigningDocumentRepository.class);
    final ContractRepository contracts = mock(ContractRepository.class);
    final ContractDocumentRepository documents = mock(ContractDocumentRepository.class);
    final SignatureRequestRepository signatures = mock(SignatureRequestRepository.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final jakarta.persistence.EntityManager em = mock(jakarta.persistence.EntityManager.class);
    final BillingTime time = mock(BillingTime.class);
    final RentalWorkflowRecord workflow = new RentalWorkflowRecord();
    final WorkflowSigningDocument signingDocument = new WorkflowSigningDocument();
    final Contract root = Contract.builder().id("root").listingId("listing").rentalRequestId("rental")
        .landlordId("owner").tenantId("tenant").currentRevisionId("root-revision")
        .status(ContractStatus.ACTIVE).signedAt(Instant.parse("2026-10-01T00:00:00Z")).build();
    final Contract envelope = Contract.builder().id("signing").parentContractId("root")
        .agreementKind("WORKFLOW").listingId("listing").rentalRequestId("rental")
        .landlordId("owner").tenantId("tenant").currentRevisionId("signing-revision").build();
    WorkflowDocumentService service;

    @BeforeEach void setup() {
        when(time.now()).thenReturn(Instant.parse("2026-11-15T03:00:00Z"));
        when(time.today()).thenReturn(LocalDate.of(2026,11,15));
        workflow.setId("rental:EARLY_EXIT"); workflow.setRentalRequestId("rental");
        workflow.setContractId("root"); workflow.setKind("EARLY_EXIT"); workflow.setStatus("APPROVED");
        workflow.setRevision(4); workflow.setSourceRevisionId("root-revision");
        workflow.setPayloadJson("{\"endExclusive\":\"2026-11-20\",\"reason\":\"Đề nghị chấm dứt được hai bên xem xét\"}");
        signingDocument.setWorkflowId(workflow.getId()); signingDocument.setSigningContractId("signing");
        signingDocument.setContentHash(WorkflowDocumentService.hash(workflow));
        when(signingDocuments.findBySigningContractId("signing")).thenReturn(Optional.of(signingDocument));
        when(workflows.findById(workflow.getId())).thenReturn(Optional.of(workflow));
        when(contracts.findById("root")).thenReturn(Optional.of(root));
        when(contracts.findByIdForUpdate("root")).thenReturn(Optional.of(root));
        var support = new RentalWorkflowSupport(workflows,mock(RentalRequestRepository.class),contracts,
            mock(ContractRevisionRepository.class),mock(StorageObjectRepository.class),mock(PaymentRequestRepository.class),
            new ObjectMapper().findAndRegisterModules(),time);
        service = new WorkflowDocumentService(support,signingDocuments,documents,signatures,
            mock(DisputeCaseRepository.class),mock(WorkflowPdfRenderer.class),mock(StorageService.class),
            mock(com.hs.contract.pdf.PdfSignatureEngine.class),events,mock(AgreementSigningNotificationWriter.class));
        var terms=mock(ContractTermsResolver.class);when(terms.at(any(),any())).thenAnswer(call->ContractRevision.builder().id(root.getCurrentRevisionId()).build());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"effectiveTerms",terms);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"em",em);
    }

    @ParameterizedTest @ValueSource(strings={"WITHDRAWN","REJECTED"})
    void lateSigningCannotApplyWithdrawnOrRejectedProposal(String status) {
        workflow.setStatus(status);
        assertThrows(AppException.class,()->service.bothSigned(envelope));
        assertNull(signingDocument.getSignedAt());
        verifyNoInteractions(events,signatures,documents);
        verify(signingDocuments,never()).saveAndFlush(any());
    }

    @Test void lateSigningCannotApplyAnOldProposalHash() {
        workflow.setPayloadJson("{\"endExclusive\":\"2026-11-21\",\"reason\":\"Nội dung đối chiếu đã được thay đổi\"}");
        assertThrows(AppException.class,()->service.bothSigned(envelope));
        assertNull(signingDocument.getSignedAt());
        verifyNoInteractions(events,signatures,documents);
    }

    @Test void lateSigningCannotApplyAfterTheLeaseRevisionChanged() {
        root.setCurrentRevisionId("new-root-revision");
        assertThrows(AppException.class,()->service.bothSigned(envelope));
        assertNull(signingDocument.getSignedAt());
        verifyNoInteractions(events,signatures,documents);
    }

    @Test void bothVerifiedSignaturesWithoutTheFinalPdfCannotApplyTheAgreement() {
        verifiedSignatures();
        assertThrows(AppException.class,()->service.bothSigned(envelope));
        assertNull(signingDocument.getSignedAt());
        verifyNoInteractions(events);
    }

    @Test void verifiedSignaturesAndFinalPdfPublishOnceWithoutRewritingTheLease() {
        verifiedSignatures();
        when(documents.findByContractIdAndRevisionId("signing","signing-revision"))
            .thenReturn(List.of(ContractDocument.builder().purpose(DocumentPurpose.SIGNED_FINAL)
                .documentType(ContractDocumentType.PDF).storageObjectId("signed-file").status(DocumentGenerationStatus.READY).build()));
        service.bothSigned(envelope); service.bothSigned(envelope);
        var locks=inOrder(contracts,em,signingDocuments);
        locks.verify(contracts).findByIdForUpdate("root");
        locks.verify(em).refresh(root,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        locks.verify(signingDocuments).findBySigningContractId("signing");
        locks.verify(em).refresh(signingDocument,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        assertEquals(time.now(),signingDocument.getSignedAt());
        assertNull(root.getAgreedEarlyEndExclusive());
        assertEquals(ContractStatus.ACTIVE,root.getStatus());
        verify(events,times(1)).publishEvent(isA(WorkflowDocumentSigned.class));
        verify(signingDocuments,times(1)).saveAndFlush(signingDocument);
        verify(contracts,never()).save(any());
    }

    @Test void identicalTermsFromAnotherProposalRoundNeverReuseTheOldSignedArtifact(){
        workflow.setHistoryJson("[{\"action\":\"PROPOSE\",\"revision\":1}]");var first=WorkflowDocumentService.hash(workflow);
        workflow.setHistoryJson("[{\"action\":\"PROPOSE\",\"revision\":1},{\"action\":\"ACCEPT\",\"revision\":2}]");assertEquals(first,WorkflowDocumentService.hash(workflow));
        workflow.setHistoryJson("[{\"action\":\"PROPOSE\",\"revision\":1},{\"action\":\"PROPOSE\",\"revision\":4}]");assertNotEquals(first,WorkflowDocumentService.hash(workflow));
    }
    @Test void signerIdentityCannotBeBorrowedFromAnotherAccount(){
        verifiedSignatures();when(signatures.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc("signing",SignerRole.TENANT)).thenReturn(Optional.of(SignatureRequest.builder().revisionId("signing-revision").status(SignatureRequestStatus.SIGNED).signerUserId("stranger").build()));
        assertThrows(AppException.class,()->service.bothSigned(envelope));verifyNoInteractions(events);
    }
    @Test void repeatCompletionAfterDeadlineIsIdempotent(){
        signingDocument.setSignedAt(time.now());when(time.today()).thenReturn(LocalDate.of(2027,1,1));
        service.bothSigned(envelope);verifyNoInteractions(events,signatures,documents);
    }
    private void verifiedSignatures() {
        for (var role : SignerRole.values()) {
            when(signatures.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc("signing",role))
                .thenReturn(Optional.of(SignatureRequest.builder().revisionId("signing-revision")
                    .status(SignatureRequestStatus.SIGNED).signerRole(role).signerUserId(role==SignerRole.LANDLORD?"owner":"tenant").build()));
        }
    }
}
