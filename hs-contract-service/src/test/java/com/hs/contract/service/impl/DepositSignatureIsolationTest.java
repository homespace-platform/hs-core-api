package com.hs.contract.service.impl;

import com.hs.contract.deposit.DepositAgreementService;
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
    @Mock SignatureRequestRepository requests;
    @Mock ContractRepository contracts;
    @Mock ContractDocumentRepository documents;
    @Mock RentalRequestRepository rentals;
    @Mock PaymentRequestRepository payments;
    @Mock ListingStatusService listingStatus;
    @Mock ParkingReservationService parking;
    @Mock RentalOnboardingService onboarding;
    @Mock DepositAgreementService depositAgreements;
    @InjectMocks SmartCaSignatureServiceImpl service;

    @Test void finalTenantSignatureCompletesDepositWithoutActivatingLeaseOrListing() throws Exception {
        var c=run(SignerRole.TENANT,ContractStatus.TENANT_SIGNATURE_PENDING);
        assertEquals(ContractStatus.ACTIVE,c.getStatus());assertNotNull(c.getSignedAt());
        verify(depositAgreements).bothSigned(c);
        verifyNoInteractions(onboarding,payments,listingStatus,parking);
        verify(rentals,never()).save(any());
        verify(documents).save(argThat(d->d.getPurpose()==DocumentPurpose.SIGNED_FINAL && "deposit".equals(d.getContractId())));
    }
    @Test void landlordSignatureOpensOnlyTheDepositTenantWindow() throws Exception {
        var c=run(SignerRole.LANDLORD,ContractStatus.LANDLORD_SIGNATURE_PENDING);
        assertEquals(ContractStatus.TENANT_SIGNATURE_PENDING,c.getStatus());assertNull(c.getSignedAt());
        verify(depositAgreements).landlordSigned(c);verify(depositAgreements,never()).bothSigned(any());
        verifyNoInteractions(onboarding,payments,listingStatus,parking);
    }
    private Contract run(SignerRole role,ContractStatus status) throws Exception {
        ReflectionTestUtils.setField(service,"depositAgreements",depositAgreements);
        ReflectionTestUtils.setField(service,"onboarding",onboarding);
        var c=Contract.builder().id("deposit").rentalRequestId("rental").agreementKind("DEPOSIT")
            .currentRevisionId("revision").contractNumber("DC-1").status(status).build();
        var request=SignatureRequest.builder().id("request").contractId("deposit").sourceDocumentId("source")
            .status(SignatureRequestStatus.EMBEDDING).signerRole(role).build();
        when(requests.findById("request")).thenReturn(Optional.of(request));
        when(contracts.findById("deposit")).thenReturn(Optional.of(c));
        when(contracts.findByIdForUpdate("deposit")).thenReturn(Optional.of(c));
        when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(com.hs.listing.model.RentalRequest.builder().id("rental").build()));
        when(documents.findById("source")).thenReturn(Optional.of(ContractDocument.builder().templateVersionId("version").build()));
        when(documents.save(any())).thenAnswer(i->i.getArgument(0));
        if(role==SignerRole.TENANT)when(requests.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc("deposit",SignerRole.LANDLORD))
            .thenReturn(Optional.of(SignatureRequest.builder().status(SignatureRequestStatus.SIGNED).build()));
        Class<?> context=Arrays.stream(SmartCaSignatureServiceImpl.class.getDeclaredClasses()).filter(t->t.getSimpleName().equals("EmbeddingContext")).findFirst().orElseThrow();
        var ctor=context.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object ctx=ctor.newInstance("request","deposit",role,"prepared",0L,8192,"provider-result","certificate","range","identity","DC-1",1,"revision");
        ReflectionTestUtils.invokeMethod(service,"finalizeSignedRequest","request","signed-storage",100L,ctx);
        assertEquals(SignatureRequestStatus.SIGNED,request.getStatus());
        return c;
    }
}
