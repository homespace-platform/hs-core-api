package com.hs.contract.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.ContractPreviewIdentity;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.*;
import com.hs.contract.pdf.PdfSignatureEngine;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ListingStatusService;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.service.StorageService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ContractPreviewTest {
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final ContractRevisionRepository revisions = mock(ContractRevisionRepository.class);
    private final ContractDocumentRepository documents = mock(ContractDocumentRepository.class);
    private final ContractTemplateVersionRepository templates = mock(ContractTemplateVersionRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final DocumentConversionService converter = mock(DocumentConversionService.class);
    private final List<ContractDocument> saved = new ArrayList<>();
    private final Contract contract = Contract.builder().id("contract").landlordId("landlord").tenantId("tenant")
            .status(ContractStatus.DRAFT).contractNumber("HD-TEST").currentRevisionId("rev").templateVersionId("template").build();
    private final ContractRevision revision = ContractRevision.builder().id("rev").contract(contract).revisionNumber(3)
            .templateVersionId("template").initialMetersSnapshot("{\"electricityInitial\":\"1250\",\"waterInitial\":\"85\"}")
            .policiesSnapshot("{\"noticeDaysBeforeTermination\":30,\"depositRefundDays\":15,\"latePaymentFeeMode\":\"FIXED_PER_DAY\",\"latePaymentFeeAmount\":100000,\"latePaymentFeeGraceDays\":0}")
            .specialTerms("Thỏa thuận riêng của hai bên.").build();
    private final ContractTemplateVersion template = ContractTemplateVersion.builder().id("template").storageObjectId("source")
            .placeholdersJson("[\"contract.specialTerms\",\"meters.electricityInitial\",\"meters.waterInitial\"]").build();
    private final ContractServiceImpl service = new ContractServiceImpl(contracts, revisions, documents, templates,
            mock(RentalRequestRepository.class), mock(ListingStatusService.class), mock(ContractDataBuilder.class),
            new ContractFieldCatalog(), new ContractRenderService(new ObjectMapper()), converter, storage, new ObjectMapper());

    @BeforeEach void setup() throws Exception {
        UserContextHolder.set(new UserContext("landlord", "landlord@test.invalid"));
        ReflectionTestUtils.setField(service, "signatureMode", "INTERNAL");
        when(contracts.findById("contract")).thenReturn(Optional.of(contract));
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        when(revisions.findById(anyString())).thenAnswer(inv -> Optional.of(revision));
        when(templates.findById("template")).thenReturn(Optional.of(template));
        when(documents.findByContractIdAndRevisionId(anyString(), anyString())).thenAnswer(inv -> new ArrayList<>(saved));
        when(documents.save(any())).thenAnswer(inv -> {
            ContractDocument doc = inv.getArgument(0);
            if (doc.getId() == null) { doc.setId(UUID.randomUUID().toString()); saved.add(doc); }
            return doc;
        });
        when(storage.downloadDirect("source")).thenReturn(word());
        when(storage.uploadDirect(any(), anyString(), anyString(), any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    StorageObjectResponse response = mock(StorageObjectResponse.class);
                    when(response.id()).thenReturn(UUID.randomUUID().toString()); return response;
                });
        when(converter.convertDocxToPdf(any(), anyString())).thenReturn(Optional.of("%PDF-test".getBytes()));
    }

    @AfterEach void clear() { UserContextHolder.clear(); }

    private byte[] word() throws Exception {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("{{contract.specialTerms}}");
            doc.createParagraph().createRun().setText("Điện {{meters.electricityInitial}}; nước {{meters.waterInitial}}");
            doc.write(out); return out.toByteArray();
        }
    }

    @Test void duplicateClicksReuseCommittedPdfAndNeverConvertAgain() {
        var first = service.triggerPreview("contract");
        var second = service.triggerPreview("contract");
        assertEquals(first.getId(), second.getId());
        assertEquals(ContractDocumentType.PDF, first.getDocumentType());
        assertEquals(2, saved.size());
        verify(converter, times(1)).convertDocxToPdf(any(), anyString());
        verify(contracts, times(2)).findByIdForUpdate("contract");
    }

    @Test void renderedWordContainsFrozenPoliciesFeeAndReadings() throws Exception {
        service.triggerPreview("contract");
        var captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(converter).convertDocxToPdf(captor.capture(), anyString());
        try (var doc = new XWPFDocument(new ByteArrayInputStream(captor.getValue()))) {
            String text = doc.getParagraphs().stream().map(p -> p.getText()).reduce("", (a,b) -> a + "\n" + b);
            assertTrue(text.contains("tối thiểu 30 ngày"));
            assertTrue(text.contains("15 ngày sau khi hoàn tất bàn giao"));
            assertTrue(text.contains("100.000 VNĐ cho mỗi ngày chậm trả"));
            assertTrue(text.contains("1250")); assertTrue(text.contains("85"));
            assertFalse(text.contains("{{"));
        }
    }

    @Test void legacyPdfDoesNotSatisfyStatusAndIsStaledOnlyAfterSuccessfulExport() {
        var legacy = ContractDocument.builder().id("legacy").revisionId("rev").purpose(DocumentPurpose.PREVIEW)
                .documentType(ContractDocumentType.PDF).status(DocumentGenerationStatus.READY).storageObjectId("old").build();
        saved.add(legacy);
        assertNull(service.getPreviewStatus("contract"));
        var generated = service.triggerPreview("contract");
        assertEquals(DocumentGenerationStatus.STALE, legacy.getStatus());
        assertEquals(generated.getId(), service.getPreviewStatus("contract").getId());
    }

    @Test void changedMetersOrModeChangeFingerprintAndSignedDocumentIsNeverStaled() {
        String before = ContractPreviewIdentity.fingerprint(revision, "INTERNAL");
        revision.setInitialMetersSnapshot("{\"electricityInitial\":\"1300\"}");
        assertNotEquals(before, ContractPreviewIdentity.fingerprint(revision, "INTERNAL"));
        assertNotEquals(before, ContractPreviewIdentity.fingerprint(revision, "SMARTCA"));
        var signed = ContractDocument.builder().id("signed").revisionId("rev").purpose(DocumentPurpose.SIGNED_FINAL)
                .documentType(ContractDocumentType.PDF).status(DocumentGenerationStatus.READY).storageObjectId("signed-storage").build();
        saved.add(signed);
        service.triggerPreview("contract");
        assertEquals(DocumentGenerationStatus.READY, signed.getStatus());
    }

    @Test void smartCaRequiresPdfAndDoesNotTreatDocxAsSuccessfulPreview() {
        ReflectionTestUtils.setField(service, "signatureMode", "SMARTCA");
        when(converter.convertDocxToPdf(any(), anyString())).thenReturn(Optional.empty());
        assertThrows(AppException.class, () -> service.triggerPreview("contract"));
        assertNull(service.getPreviewStatus("contract"));
        verify(storage, never()).uploadDirect(any(), anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test void smartCaAddsSignaturePageOnlyOnceAcrossRetries() throws Exception {
        ReflectionTestUtils.setField(service, "signatureMode", "SMARTCA");
        PdfSignatureEngine engine = mock(PdfSignatureEngine.class);
        ReflectionTestUtils.setField(service, "pdfSignatureEngine", engine);
        when(engine.addSignaturePage(any())).thenAnswer(inv -> inv.getArgument(0));
        service.triggerPreview("contract"); service.triggerPreview("contract");
        verify(engine, times(1)).addSignaturePage(any());
    }

    @Test void missingSpecialTermsPlaceholderCannotSilentlyOmitPolicies() {
        template.setPlaceholdersJson("[\"meters.electricityInitial\"]");
        assertFalse(service.getCompleteness("contract").isComplete());
        assertThrows(AppException.class, () -> service.triggerPreview("contract"));
        verifyNoInteractions(converter);
    }

    @Test void sendingLegacyPreviewRegeneratesAndPromotesOnlyMatchingCurrentDocuments() {
        var legacy = ContractDocument.builder().id("legacy").revisionId("rev").purpose(DocumentPurpose.PREVIEW)
                .documentType(ContractDocumentType.PDF).status(DocumentGenerationStatus.READY).storageObjectId("old").build();
        saved.add(legacy);
        when(contracts.save(contract)).thenReturn(contract);
        service.sendToTenant("contract");
        assertEquals(ContractStatus.PENDING_REVIEW, contract.getStatus());
        assertEquals(DocumentGenerationStatus.STALE, legacy.getStatus());
        assertEquals(DocumentPurpose.PREVIEW, legacy.getPurpose());
        assertEquals(2, saved.stream().filter(doc -> doc.getPurpose() == DocumentPurpose.OFFICIAL).count());
        verify(converter, times(1)).convertDocxToPdf(any(), anyString());
    }

    @Test void signedContractAndAnotherUserCannotRegenerate() {
        contract.setStatus(ContractStatus.ACTIVE);
        assertThrows(AppException.class, () -> service.triggerPreview("contract"));
        contract.setStatus(ContractStatus.DRAFT);
        UserContextHolder.set(new UserContext("stranger", "stranger@test.invalid"));
        assertThrows(AppException.class, () -> service.getPreviewStatus("contract"));
        assertThrows(AppException.class, () -> service.triggerPreview("contract"));
        verifyNoInteractions(converter);
    }
}
