package com.hs.contract.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.ContractPreviewIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmartCaPreviewSourceTest {
    @Mock ContractDocumentRepository documents;
    @Mock ContractRevisionRepository revisions;
    @InjectMocks SmartCaSignatureServiceImpl service;
    private final Contract contract = Contract.builder().id("c").currentRevisionId("r").build();
    private final ContractRevision revision = ContractRevision.builder().id("r").specialTerms("Current terms").build();

    @Test void landlordMustUseCurrentFingerprintNotLegacyOrStalePdf() {
        ReflectionTestUtils.setField(service, "signatureMode", "SMARTCA");
        when(revisions.findById("r")).thenReturn(Optional.of(revision));
        var legacy = pdf(DocumentPurpose.PREVIEW, null);
        when(documents.findByContractIdAndRevisionId("c", "r")).thenReturn(List.of(legacy));
        assertThrows(AppException.class, () -> source(SignerRole.LANDLORD));
        var current = pdf(DocumentPurpose.PREVIEW, ContractPreviewIdentity.fingerprint(revision, "SMARTCA"));
        when(documents.findByContractIdAndRevisionId("c", "r")).thenReturn(List.of(legacy, current));
        assertSame(current, source(SignerRole.LANDLORD));
        current.setStatus(DocumentGenerationStatus.STALE);
        assertThrows(AppException.class, () -> source(SignerRole.LANDLORD));
    }
    @Test void tenantContinuesSigningOriginalLandlordSignedPdfWithoutRegeneration() {
        var signed = pdf(DocumentPurpose.SIGNED_LANDLORD, null);
        when(documents.findByContractIdAndRevisionId("c", "r")).thenReturn(List.of(signed));
        assertSame(signed, source(SignerRole.TENANT));
        verifyNoInteractions(revisions);
    }
    private ContractDocument source(SignerRole role) {
        return ReflectionTestUtils.invokeMethod(service, "findSigningSourcePdf", contract, role);
    }
    private ContractDocument pdf(DocumentPurpose purpose, String fingerprint) {
        return ContractDocument.builder().documentType(ContractDocumentType.PDF).purpose(purpose)
                .status(DocumentGenerationStatus.READY).storageObjectId("stored")
                .renderFingerprint(fingerprint).build();
    }
}
