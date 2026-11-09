package com.hs.contract.service.impl;

import com.hs.contract.deposit.DepositAgreementService;
import com.hs.contract.blockchain.persistence.ContractDocumentProofEnqueuer;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.RentalOnboardingService;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.*;
import com.hs.payment.repository.PaymentRequestRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tests the final verified-PDF transaction boundary, not a real VNPT signature. */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness=org.mockito.quality.Strictness.LENIENT)
class WorkflowSignatureIsolationTest {
    boolean sourceStale;
    @Mock SignatureRequestRepository requests;
    @Mock ContractRepository contracts;
    @Mock ContractDocumentRepository documents;
    @Mock RentalRequestRepository rentals;
    @Mock PaymentRequestRepository payments;
    @Mock ListingStatusService listingStatus;
    @Mock ParkingReservationService parking;
    @Mock RentalOnboardingService onboarding;
    @Mock DepositAgreementService depositAgreements;
    @Mock ContractDocumentProofEnqueuer documentProofEnqueuer;
    @Mock com.hs.contract.service.ContractLifecycleService lifecycle;
    @Mock jakarta.persistence.EntityManager entityManager;
    @InjectMocks SmartCaSignatureServiceImpl service;

    @Test void finalWorkflowSignatureUsesOnlyTheDedicatedWorkflowCommitter() throws Exception {
        var c=run(SignerRole.TENANT,ContractStatus.TENANT_SIGNATURE_PENDING);
        assertEquals(ContractStatus.ACTIVE,c.getStatus());assertNotNull(c.getSignedAt());
        verify(lifecycle).applyRenewalAfterSignatures(c);
        verifyNoInteractions(onboarding,payments,listingStatus,parking);
        verify(rentals,never()).save(any());
        verify(documents).save(argThat(d->d.getPurpose()==DocumentPurpose.SIGNED_FINAL && "workflow".equals(d.getContractId())
                && "DC-1_da_ky_hai_ben.pdf".equals(d.getFileName())));
    }
    @Test void firstWorkflowSignatureOnlyOpensTheSecondSignerWindow() throws Exception {
        var c=run(SignerRole.LANDLORD,ContractStatus.LANDLORD_SIGNATURE_PENDING);
        assertEquals(ContractStatus.TENANT_SIGNATURE_PENDING,c.getStatus());assertNull(c.getSignedAt());
        verify(lifecycle,never()).applyRenewalAfterSignatures(any());verifyNoInteractions(depositAgreements);
        verifyNoInteractions(onboarding,payments,listingStatus,parking);
    }
    @Test void lateProviderResultForReplacedSourceNeverWritesOrActivatesAWorkflow() {
        sourceStale=true;assertThrows(com.hs.common.advice.entity.AppException.class,()->run(SignerRole.TENANT,ContractStatus.TENANT_SIGNATURE_PENDING));verify(documents,never()).save(any());verify(lifecycle,never()).applyRenewalAfterSignatures(any());verifyNoInteractions(onboarding,payments,listingStatus,parking);
    }
    private Contract run(SignerRole role,ContractStatus status) throws Exception {
        ReflectionTestUtils.setField(service,"depositAgreements",depositAgreements);
        ReflectionTestUtils.setField(service,"documentProofEnqueuer",documentProofEnqueuer);
        ReflectionTestUtils.setField(service,"onboarding",onboarding);ReflectionTestUtils.setField(service,"lifecycle",lifecycle);
        ReflectionTestUtils.setField(service,"entityManager",entityManager);
        var c=Contract.builder().id("workflow").rentalRequestId("rental").agreementKind("WORKFLOW").parentContractId("root")
            .currentRevisionId("revision").contractNumber("DC-1").status(status).build();
        var request=SignatureRequest.builder().id("request").contractId("workflow").sourceDocumentId("source")
            .status(SignatureRequestStatus.EMBEDDING).signerRole(role).build();
        when(requests.findById("request")).thenReturn(Optional.of(request));
        when(contracts.findById("workflow")).thenReturn(Optional.of(c));
        when(contracts.findByIdForUpdate("workflow")).thenReturn(Optional.of(c));
        when(contracts.findByIdForUpdate("root")).thenReturn(Optional.of(Contract.builder().id("root").build()));
        when(documents.findById("source")).thenReturn(Optional.of(ContractDocument.builder().templateVersionId("version").build()));
        when(documents.save(any())).thenAnswer(i->i.getArgument(0));
        if(role==SignerRole.TENANT)when(requests.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc("workflow",SignerRole.LANDLORD))
            .thenReturn(Optional.of(SignatureRequest.builder().status(SignatureRequestStatus.SIGNED).build()));
        if(sourceStale)doThrow(new com.hs.common.advice.entity.AppException(com.hs.contract.advice.ContractErrorCode.LIFECYCLE_NOT_ALLOWED)).when(lifecycle).validateAgreementSigning(c);
        Class<?> context=Arrays.stream(SmartCaSignatureServiceImpl.class.getDeclaredClasses()).filter(t->t.getSimpleName().equals("EmbeddingContext")).findFirst().orElseThrow();
        var ctor=context.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object ctx=ctor.newInstance("request","workflow",role,"prepared",0L,8192,"provider-result","certificate","range","identity","DC-1",1,"revision");
        ReflectionTestUtils.invokeMethod(service,"finalizeSignedRequest","request","signed-storage",100L,ctx,
                "0x" + "a".repeat(64), "s3-version-final");
        assertEquals(SignatureRequestStatus.SIGNED,request.getStatus());
        return c;
    }
}
