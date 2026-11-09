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
class DepositSignatureIsolationTest {
    private String signedVersion = "s3-version-final";
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
    @Mock jakarta.persistence.EntityManager entityManager;
    @InjectMocks SmartCaSignatureServiceImpl service;

    @Test void finalTenantSignatureCompletesDepositWithoutActivatingLeaseOrListing() throws Exception {
        var c=run(SignerRole.TENANT,ContractStatus.TENANT_SIGNATURE_PENDING);
        assertEquals(ContractStatus.ACTIVE,c.getStatus());assertNotNull(c.getSignedAt());
        verify(depositAgreements).bothSigned(c);
        verifyNoInteractions(onboarding,payments,listingStatus,parking);
        verify(rentals,never()).save(any());
        verify(documents).save(argThat(d->d.getPurpose()==DocumentPurpose.SIGNED_FINAL && "deposit".equals(d.getContractId())
                && ("0x" + "a".repeat(64)).equals(d.getSignedSha256()) && "s3-version-final".equals(d.getSignedS3VersionId())));
        verify(documentProofEnqueuer).enqueue(eq(c), argThat(d -> d.getPurpose() == DocumentPurpose.SIGNED_FINAL),
                eq("0x" + "a".repeat(64)));
    }
    @Test void landlordSignatureOpensOnlyTheDepositTenantWindow() throws Exception {
        var c=run(SignerRole.LANDLORD,ContractStatus.LANDLORD_SIGNATURE_PENDING);
        assertEquals(ContractStatus.TENANT_SIGNATURE_PENDING,c.getStatus());assertNull(c.getSignedAt());
        verify(depositAgreements).landlordSigned(c);verify(depositAgreements,never()).bothSigned(any());
        verifyNoInteractions(onboarding,payments,listingStatus,parking);
        verifyNoInteractions(documentProofEnqueuer);
    }
    @Test void finalSignatureWithoutPinnedVersionCannotPersistOrCompleteDeposit() {
        signedVersion = "null";
        var failure = assertThrows(com.hs.common.advice.entity.AppException.class,
                () -> run(SignerRole.TENANT, ContractStatus.TENANT_SIGNATURE_PENDING, false));
        assertEquals(6086, failure.getCode());
        verify(documents, never()).save(any());
        verify(depositAgreements, never()).bothSigned(any());
        verifyNoInteractions(documentProofEnqueuer);
    }
    private Contract run(SignerRole role,ContractStatus status) throws Exception {
        return run(role, status, true);
    }
    private Contract run(SignerRole role,ContractStatus status, boolean completes) throws Exception {
        ReflectionTestUtils.setField(service,"depositAgreements",depositAgreements);
        ReflectionTestUtils.setField(service,"documentProofEnqueuer",documentProofEnqueuer);
        ReflectionTestUtils.setField(service,"onboarding",onboarding);
        ReflectionTestUtils.setField(service,"entityManager",entityManager);
        var c=Contract.builder().id("deposit").rentalRequestId("rental").agreementKind("DEPOSIT")
            .currentRevisionId("revision").contractNumber("DC-1").status(status).build();
        var request=SignatureRequest.builder().id("request").contractId("deposit").sourceDocumentId("source")
            .status(SignatureRequestStatus.EMBEDDING).signerRole(role).build();
        when(requests.findById("request")).thenReturn(Optional.of(request));
        when(contracts.findById("deposit")).thenReturn(Optional.of(c));
        when(contracts.findByIdForUpdate("deposit")).thenReturn(Optional.of(c));
        when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(com.hs.listing.model.RentalRequest.builder().id("rental").build()));
        when(documents.findById("source")).thenReturn(Optional.of(ContractDocument.builder().templateVersionId("version").build()));
        if(completes)when(documents.save(any())).thenAnswer(i->i.getArgument(0));
        if(completes && role==SignerRole.TENANT)when(requests.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc("deposit",SignerRole.LANDLORD))
            .thenReturn(Optional.of(SignatureRequest.builder().status(SignatureRequestStatus.SIGNED).build()));
        Class<?> context=Arrays.stream(SmartCaSignatureServiceImpl.class.getDeclaredClasses()).filter(t->t.getSimpleName().equals("EmbeddingContext")).findFirst().orElseThrow();
        var ctor=context.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object ctx=ctor.newInstance("request","deposit",role,"prepared",0L,8192,"provider-result","certificate","range","identity","DC-1",1,"revision");
        ReflectionTestUtils.invokeMethod(service,"finalizeSignedRequest","request","signed-storage",100L,ctx,
                "0x" + "a".repeat(64), signedVersion);
        assertEquals(SignatureRequestStatus.SIGNED,request.getStatus());
        return c;
    }
}
