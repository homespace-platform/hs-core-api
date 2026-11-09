package com.hs.contract.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.blockchain.persistence.ContractDocumentProofEnqueuer;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.*;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ListingStatusService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmartCaDraftIntegrityTest {
    @Mock ContractRepository contracts;
    @Mock ContractDocumentRepository documents;
    @Mock SignatureRequestRepository requests;
    @Mock RentalRequestRepository rentals;
    @Mock ContractDraftIntegrity integrity;
    @Mock RentalOnboardingService onboarding;
    @Mock EntityManager entityManager;
    @Mock ListingStatusService listingStatus;
    @Mock ContractDocumentProofEnqueuer documentProofEnqueuer;
    @InjectMocks SmartCaSignatureServiceImpl service;

    @BeforeEach void wireFieldDependencies() {
        ReflectionTestUtils.setField(service,"draftIntegrity",integrity);
        ReflectionTestUtils.setField(service,"documentProofEnqueuer",documentProofEnqueuer);
        ReflectionTestUtils.setField(service,"onboarding",onboarding);
        ReflectionTestUtils.setField(service,"entityManager",entityManager);
    }

    @Test void preparedPdfRevisionIsCheckedAfterRefreshEvenWhenContextAndLockedContractAreSameEntity() throws Exception {
        var c=root();
        when(contracts.findByIdForUpdate("root")).thenReturn(Optional.of(c));
        when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(RentalRequest.builder().id("rental").build()));
        var source=ContractDocument.builder().id("pdf").revisionId("reviewed").build();
        var context=context("ContractSigningContext",c,null,SignerRole.LANDLORD,null,source,"doc","transaction");
        // Hibernate refresh may mutate the very same managed instance captured earlier.
        doAnswer(i->{if(i.getArgument(0)==c)c.setCurrentRevisionId("changed-while-preparing");return null;}).when(entityManager).refresh(any());
        var error=assertThrows(AppException.class,()->ReflectionTestUtils.invokeMethod(service,"createSignatureRequest",context,null,"prepared"));
        assertEquals(6028,error.getCode());verify(integrity).validateForDocument(c);
        verifyNoInteractions(requests,documents,listingStatus);
    }
    @Test void legacyInconsistentSnapshotCannotFinalizeOrActivateAfterProviderHasReturnedSignature() throws Exception {
        var c=root();
        var request=SignatureRequest.builder().id("request").contractId("root").sourceDocumentId("source")
                .status(SignatureRequestStatus.EMBEDDING).signerRole(SignerRole.TENANT).build();
        when(requests.findById("request")).thenReturn(Optional.of(request));
        when(contracts.findById("root")).thenReturn(Optional.of(c));
        when(contracts.findByIdForUpdate("root")).thenReturn(Optional.of(c));
        when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(RentalRequest.builder().id("rental").build()));
        doThrow(new AppException(ContractErrorCode.CONTRACT_DRAFT_READ_ONLY)).when(integrity).validateForDocument(c);
        var context=context("EmbeddingContext","request","root",SignerRole.TENANT,"prepared",0L,8192,"provider-result","certificate","range","identity","HD",1,"reviewed");
        assertEquals(6070,assertThrows(AppException.class,()->ReflectionTestUtils.invokeMethod(service,"finalizeSignedRequest","request","signed-storage",100L,context,
                "0x" + "a".repeat(64), "s3-version-final")).getCode());
        assertEquals(SignatureRequestStatus.EMBEDDING,request.getStatus());
        verify(requests,never()).save(any());verify(contracts,never()).save(any());verifyNoInteractions(documents,listingStatus);
        verifyNoInteractions(documentProofEnqueuer);
    }
    private Contract root(){return Contract.builder().id("root").rentalRequestId("rental").tenantId("tenant")
            .currentRevisionId("reviewed").status(ContractStatus.DRAFT).paymentStatus(ContractPaymentStatus.PAID).build();}
    private Object context(String name,Object...args) throws Exception {
        var type=Arrays.stream(SmartCaSignatureServiceImpl.class.getDeclaredClasses()).filter(t->t.getSimpleName().equals(name)).findFirst().orElseThrow();
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);return constructor.newInstance(args);
    }
}
