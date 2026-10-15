package com.hs.contract.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.model.ContractTemplateVersion;
import com.hs.contract.model.constant.ContractDocumentType;
import com.hs.contract.model.constant.ContractPaymentStatus;
import com.hs.contract.model.constant.ContractPartyRole;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.model.constant.DocumentGenerationStatus;
import com.hs.contract.model.constant.DocumentPurpose;
import com.hs.contract.repository.ContractDocumentRepository;
import com.hs.contract.repository.ContractRepository;
import com.hs.contract.repository.ContractRevisionRepository;
import com.hs.contract.repository.ContractTemplateVersionRepository;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.ContractDataBuilder;
import com.hs.contract.service.engine.ContractFieldCatalog;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.contract.dto.request.CreateContractDraftRequest;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.service.ListingStatusService;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ContractServiceImplTest {
    private final class EffectiveDocumentFixture {
        final ContractRepository contracts = mock(ContractRepository.class);
        final ContractRevisionRepository revisions = mock(ContractRevisionRepository.class);
        final ContractDocumentRepository documents = mock(ContractDocumentRepository.class);
        final com.hs.contract.repository.ContractAmendmentRepository amendments = mock(com.hs.contract.repository.ContractAmendmentRepository.class);
        final java.util.Map<String,Contract> owners = new java.util.HashMap<>();
        final java.util.Map<String,ContractRevision> snapshots = new java.util.HashMap<>();
        final java.util.List<com.hs.contract.model.ContractAmendment> history = new java.util.ArrayList<>();
        final java.util.List<ContractDocument> files = new java.util.ArrayList<>();
        final Contract root = Contract.builder().id("root").currentRevisionId("root-revision").landlordId("owner").tenantId("tenant")
                .status(ContractStatus.ACTIVE).signedAt(java.time.Instant.parse("2026-10-15T03:00:00Z")).build();
        final ContractServiceImpl service = createService(contracts,revisions,documents,mock(ContractTemplateVersionRepository.class));
        EffectiveDocumentFixture() {
            owners.put("root",root);snapshots.put("root-revision",ContractRevision.builder().id("root-revision").contract(root).build());
            when(contracts.findById(anyString())).thenAnswer(i -> Optional.ofNullable(owners.get(i.getArgument(0))));
            when(revisions.findById(anyString())).thenAnswer(i -> Optional.ofNullable(snapshots.get(i.getArgument(0))));
            when(amendments.findByContractIdOrderByCreatedAtDesc("root")).thenAnswer(i -> history);
            when(documents.findByContractIdAndRevisionId(anyString(),anyString())).thenAnswer(i -> files.stream()
                    .filter(d -> d.getContractId().equals(i.getArgument(0)) && d.getRevisionId().equals(i.getArgument(1))).toList());
            var storage=mock(StorageService.class);
            when(storage.createViewUrl(anyString())).thenAnswer(i -> new com.hs.storage.dto.response.StorageUrlResponse("https://files.example/view/"+i.getArgument(0),java.time.Instant.parse("2026-10-15T10:00:00Z")));
            when(storage.createDownloadUrl(anyString())).thenAnswer(i -> new com.hs.storage.dto.response.StorageUrlResponse("https://files.example/download/"+i.getArgument(0),java.time.Instant.parse("2026-10-15T10:00:00Z")));
            org.springframework.test.util.ReflectionTestUtils.setField(service,"storageService",storage);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"termsResolver",new com.hs.contract.service.ContractTermsResolver(amendments,revisions));
            at("2026-11-15T10:00:00");UserContextHolder.set(new UserContext("owner","owner@example.com"));
            signedPdf("root");
        }
        void at(String dateTime) {
            var environment=mock(org.springframework.core.env.Environment.class);
            when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
            org.springframework.test.util.ReflectionTestUtils.setField(service,"billingTime",new com.hs.common.time.BillingTime(dateTime,environment));
        }
        com.hs.contract.model.ContractAmendment appendix(String id,String status,String date,String signedAt) {
            var child=Contract.builder().id(id).parentContractId("root").agreementKind("AMENDMENT").currentRevisionId(id+"-revision")
                    .landlordId("owner").tenantId("tenant").status(ContractStatus.ACTIVE).build();
            owners.put(id,child);snapshots.put(child.getCurrentRevisionId(),ContractRevision.builder().id(child.getCurrentRevisionId()).contract(child).build());
            var proposal=com.hs.contract.model.ContractAmendment.builder().id(id).contractId("root").agreementContractId(id).termsRevisionId(child.getCurrentRevisionId())
                    .status(status).effectiveOn(java.time.LocalDate.parse(date)).signedAt(signedAt==null?null:java.time.Instant.parse(signedAt)).build();
            history.add(proposal);return proposal;
        }
        ContractDocument signedPdf(String owner) {
            var file=ContractDocument.builder().id(owner+"-pdf").contractId(owner).revisionId(owners.get(owner).getCurrentRevisionId())
                    .documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.SIGNED_FINAL).status(DocumentGenerationStatus.READY)
                    .storageObjectId(owner+"-object").fileName(owner+".pdf").generatedAt(java.time.Instant.parse("2026-10-15T05:00:00Z")).build();
            files.add(file);return file;
        }
    }
    @Test void effectivePdfChangesExactlyAtVietnamMidnightAndUsesSimulatedTimeForBothParties() {
        var f=new EffectiveDocumentFixture();
        var a=f.appendix("november","SCHEDULED","2026-11-15","2026-10-20T03:00:00Z");f.signedPdf("november");
        for(String actor:List.of("owner","tenant")) {
            UserContextHolder.set(new UserContext(actor,actor+"@example.com"));
            f.at("2026-11-14T16:59:59Z");assertEquals("root-pdf",f.service.getEffectiveDocument("root").getId());
            f.at("2026-11-14T17:00:00Z");var current=f.service.getEffectiveDocument("root");
            assertEquals("november-pdf",current.getId());assertEquals("november",current.getContractId());assertEquals("november-revision",current.getRevisionId());
            assertEquals("https://files.example/view/november-object",current.getViewUrl());
            assertEquals("https://files.example/download/november-object",current.getDownloadUrl());
            f.at("2026-11-15T10:00:00");assertEquals("november-pdf",f.service.getEffectiveDocument("root").getId());
        }
        assertEquals("SCHEDULED",a.getStatus());assertEquals("root-revision",f.root.getCurrentRevisionId());
        verify(f.contracts,never()).save(any());verify(f.documents,never()).save(any());verify(f.amendments,never()).save(any());
    }
    @Test void effectivePdfSelectsLatestEffectiveSignedTermsNotNewestFileOrFutureOrSupersededProposal() {
        var f=new EffectiveDocumentFixture();
        f.appendix("november","APPLIED","2026-11-15","2026-10-20T03:00:00Z");f.signedPdf("november");
        f.appendix("december","SCHEDULED","2026-12-15","2026-10-19T03:00:00Z");f.signedPdf("december");
        f.appendix("superseded","SUPERSEDED","2026-11-15","2026-10-22T03:00:00Z");f.signedPdf("superseded");
        for(String status:List.of("REQUESTED","APPROVED","REJECTED","WITHDRAWN","EXPIRED","APPLIED")) {
            f.appendix(status,status,"2026-11-15",null);f.signedPdf(status);
        }
        f.files.getFirst().setGeneratedAt(java.time.Instant.parse("2099-01-01T00:00:00Z"));
        assertEquals("november-pdf",f.service.getEffectiveDocument("root").getId());
        f.at("2026-12-15T00:00:00");assertEquals("december-pdf",f.service.getEffectiveDocument("root").getId());
        f.at("2026-11-14T23:59:59");assertEquals("root-pdf",f.service.getEffectiveDocument("root").getId());
    }
    @Test void missingEffectiveSignedPdfNeverFallsBackToOldRootOrAnUnsignedDraft() {
        var f=new EffectiveDocumentFixture();f.appendix("november","APPLIED","2026-11-15","2026-10-20T03:00:00Z");
        assertNull(f.service.getEffectiveDocument("root"));
        for(DocumentPurpose purpose:List.of(DocumentPurpose.PREVIEW,DocumentPurpose.OFFICIAL,DocumentPurpose.SIGNED_LANDLORD)) {
            f.files.clear();f.signedPdf("root");f.signedPdf("november").setPurpose(purpose);assertNull(f.service.getEffectiveDocument("root"));
        }
        for(DocumentGenerationStatus status:List.of(DocumentGenerationStatus.STALE,DocumentGenerationStatus.FAILED,DocumentGenerationStatus.GENERATING)) {
            f.files.clear();f.signedPdf("root");f.signedPdf("november").setStatus(status);assertNull(f.service.getEffectiveDocument("root"));
        }
        f.files.clear();var file=f.signedPdf("november");file.setDocumentType(ContractDocumentType.DOCX);assertNull(f.service.getEffectiveDocument("root"));
        file.setDocumentType(ContractDocumentType.PDF);file.setStorageObjectId(" ");assertNull(f.service.getEffectiveDocument("root"));
        file.setStorageObjectId("object");file.setRevisionId("another-revision");assertNull(f.service.getEffectiveDocument("root"));
    }
    @Test void effectivePdfRejectsOutsidersAndRevisionsOutsideTheRequestedRoot() {
        var f=new EffectiveDocumentFixture();f.appendix("foreign","APPLIED","2026-11-15","2026-10-20T03:00:00Z");f.signedPdf("foreign");
        UserContextHolder.set(new UserContext("outsider","outsider@example.com"));
        assertThrows(AppException.class,()->f.service.getEffectiveDocument("root"));verifyNoInteractions(f.amendments);
        UserContextHolder.set(new UserContext("tenant","tenant@example.com"));f.owners.get("foreign").setParentContractId("another-root");
        assertThrows(AppException.class,()->f.service.getEffectiveDocument("root"));
        verify(f.documents,never()).findByContractIdAndRevisionId(any(),any());
    }
    @Test void openingFutureAppendixDossierStillResolvesCurrentlyEffectiveRootTermsForEitherParty() {
        var f=new EffectiveDocumentFixture();
        f.appendix("pl1","APPLIED","2026-11-15","2026-10-20T03:00:00Z");f.signedPdf("pl1");
        f.appendix("pl2","SCHEDULED","2026-12-15","2026-11-15T03:00:00Z");f.signedPdf("pl2");
        for(String actor:List.of("owner","tenant")) {
            UserContextHolder.set(new UserContext(actor,actor+"@example.com"));
            f.at("2026-11-15T10:00:00");
            assertEquals("pl1-pdf",f.service.getEffectiveDocument("root").getId());
            assertEquals("pl1-pdf",f.service.getEffectiveDocument("pl2").getId());
            f.at("2026-12-14T23:59:59");assertEquals("pl1-pdf",f.service.getEffectiveDocument("pl2").getId());
            f.at("2026-12-15T00:00:00");assertEquals("pl2-pdf",f.service.getEffectiveDocument("pl2").getId());
        }
    }

    @Test void archivedFilesAreReadableByBothPartiesWithoutChangingTheirStatusOrSignedHistory() {
        var contracts=mock(ContractRepository.class);var docs=mock(ContractDocumentRepository.class);
        var service=createService(contracts,mock(ContractRevisionRepository.class),docs,mock(ContractTemplateVersionRepository.class));
        var storage=mock(StorageService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"storageService",storage);
        var contract=Contract.builder().id("root").landlordId("owner").tenantId("tenant").status(ContractStatus.ACTIVE).currentRevisionId("signed-revision").build();
        var old=ContractDocument.builder().id("old-file").contractId("root").revisionId("old-revision").documentType(ContractDocumentType.DOCX)
                .purpose(DocumentPurpose.PREVIEW).status(DocumentGenerationStatus.STALE).storageObjectId("old-object").fileName("old.docx").build();
        when(contracts.findById("root")).thenReturn(Optional.of(contract));
        when(docs.findById("old-file")).thenReturn(Optional.of(old));
        when(docs.findByContractIdOrderByGeneratedAtDesc("root")).thenReturn(List.of(old));
        when(storage.createViewUrl("old-object")).thenReturn(new com.hs.storage.dto.response.StorageUrlResponse("https://files.example/old/view",java.time.Instant.parse("2026-10-15T10:00:00Z")));
        when(storage.createDownloadUrl("old-object")).thenReturn(new com.hs.storage.dto.response.StorageUrlResponse("https://files.example/old/download",java.time.Instant.parse("2026-10-15T10:00:00Z")));
        for(String actor:List.of("owner","tenant")) {
            UserContextHolder.set(new UserContext(actor,actor+"@example.com"));
            assertEquals("https://files.example/old/view",service.getDocumentsByContract("root").getFirst().getViewUrl());
            assertEquals("https://files.example/old/download",service.getDocument("old-file").getDownloadUrl());
        }
        assertEquals(DocumentGenerationStatus.STALE,old.getStatus());assertEquals("signed-revision",contract.getCurrentRevisionId());
        verify(docs,never()).save(any());
    }
    @Test void documentHistoryRejectsStrangersAndKeepsUnsignedRootPrivateButAllowsApprovedAppendixParties() {
        var contracts=mock(ContractRepository.class);var docs=mock(ContractDocumentRepository.class);
        var service=createService(contracts,mock(ContractRevisionRepository.class),docs,mock(ContractTemplateVersionRepository.class));
        var root=Contract.builder().id("root").landlordId("owner").tenantId("tenant").status(ContractStatus.DRAFT).build();
        var appendix=Contract.builder().id("appendix").parentContractId("root").agreementKind("AMENDMENT").landlordId("owner").tenantId("tenant").status(ContractStatus.DRAFT).build();
        when(contracts.findById("root")).thenReturn(Optional.of(root));when(contracts.findById("appendix")).thenReturn(Optional.of(appendix));
        when(docs.findByContractIdOrderByGeneratedAtDesc("appendix")).thenReturn(List.of());
        UserContextHolder.set(new UserContext("stranger","stranger@example.com"));
        assertThrows(AppException.class,()->service.getDocumentsByContract("root"));assertThrows(AppException.class,()->service.getDocumentsByContract("appendix"));
        verify(docs,never()).findByContractIdOrderByGeneratedAtDesc(any());
        UserContextHolder.set(new UserContext("tenant","tenant@example.com"));
        assertThrows(AppException.class,()->service.getDocumentsByContract("root"));
        assertEquals(List.of(),service.getDocumentsByContract("appendix"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invoiceContractListIsScopedToTenantAndBillableStatusesBeforePagination() {
        ContractRepository contracts = mock(ContractRepository.class);
        ContractServiceImpl service = createService(contracts, mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class), mock(ContractTemplateVersionRepository.class));
        when(contracts.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.getContractsForCurrentUser("tenant-1", null, ContractPartyRole.TENANT, true, 1, 12);

        ArgumentCaptor<Specification> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(contracts).findAll(specCaptor.capture(), any(Pageable.class));
        Root<Contract> root = mock(Root.class);
        Path statusPath = mock(Path.class);
        when(root.get("status")).thenReturn(statusPath);
        specCaptor.getValue().toPredicate(root, null, mock(CriteriaBuilder.class));
        verify(root).get("tenantId");
        verify(root, never()).get("landlordId");
        verify(statusPath).in(ContractStatus.ACTIVE, ContractStatus.EXPIRED, ContractStatus.CLOSED, ContractStatus.TERMINATED);
        verify(root).get("parentContractId");
    }

    @Test
    void editingUnsignedDraftReplacesOldCalendarFifthWithLeaseAnchoredDueDate() {
        ContractRepository contracts = mock(ContractRepository.class);
        ContractRevisionRepository revisions = mock(ContractRevisionRepository.class);
        ContractDocumentRepository documents = mock(ContractDocumentRepository.class);
        ContractServiceImpl service = createService(contracts, revisions, documents,
                mock(ContractTemplateVersionRepository.class));
        Contract contract = Contract.builder().id("contract-1").landlordId("landlord-1")
                .tenantId("tenant-1").status(ContractStatus.DRAFT).build();
        when(contracts.findById("contract-1")).thenReturn(Optional.of(contract));
        when(contracts.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));
        when(revisions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(documents.findByContractIdOrderByGeneratedAtDesc("contract-1")).thenReturn(List.of());
        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));
        UpdateContractRevisionRequest request = new UpdateContractRevisionRequest();
        request.setFinancial(Map.of("paymentDueDay", "Từ ngày 01 đến ngày 05 hàng tháng"));
        request.setPolicies(Map.of("latePaymentFeeMode", "NONE",
                "overdueLandlordTerminationAfterFiveDays", true));

        var updated = service.updateRevision("contract-1", request);

        assertEquals(4, updated.getFinancial().get("paymentDueOffsetDays"));
        assertEquals(updated.getFinancial().get("paymentDueDay"),
                updated.getPolicies().get("paymentDueDay"));
        assertEquals(false, updated.getPolicies().get("overdueLandlordTerminationAfterFiveDays"));
        request.setSpecialTerms("bên cho thuê vẫn có thể thực hiện quyền chấm dứt đã thỏa thuận");
        var withClause = service.updateRevision("contract-1", request);
        assertEquals(true, withClause.getPolicies().get("overdueLandlordTerminationAfterFiveDays"));
    }

    @Test
    void lateFeeCannotBeSavedWhenWordTemplateCannotShowSignedClause() {
        ContractRepository contracts = mock(ContractRepository.class);
        ContractTemplateVersionRepository templates = mock(ContractTemplateVersionRepository.class);
        ContractServiceImpl service = createService(contracts, mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class), templates);
        Contract contract = Contract.builder().id("contract-1").landlordId("landlord-1")
                .tenantId("tenant-1").templateVersionId("template-version-1")
                .status(ContractStatus.DRAFT).build();
        when(contracts.findById("contract-1")).thenReturn(Optional.of(contract));
        when(contracts.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));
        when(templates.findById("template-version-1")).thenReturn(Optional.of(
                ContractTemplateVersion.builder().id("template-version-1").placeholdersJson("[]").build()));
        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));
        var request = new com.hs.contract.dto.request.UpdateContractRevisionRequest();
        request.setPolicies(Map.of("latePaymentFeeMode", "FIXED_ONCE",
                "latePaymentFeeAmount", 50000, "latePaymentFeeGraceDays", 0));

        assertThrows(AppException.class, () -> service.updateRevision("contract-1", request));
    }

    @AfterEach
    void clearUserContext() {
        UserContextHolder.clear();
    }

    @Test
    void sendToTenantMarksCurrentReadyDocumentsOfficialAndChangesStatus() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        ContractRevisionRepository revisionRepository = mock(ContractRevisionRepository.class);
        ContractDocumentRepository documentRepository = mock(ContractDocumentRepository.class);
        ContractTemplateVersionRepository templateVersionRepository = mock(ContractTemplateVersionRepository.class);

        ContractServiceImpl service = createService(
                contractRepository,
                revisionRepository,
                documentRepository,
                templateVersionRepository
        );

        Contract contract = Contract.builder()
                .id("contract-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .currentRevisionId("revision-1")
                .templateVersionId("template-version-1")
                .status(ContractStatus.DRAFT)
                .build();
        ContractRevision revision = ContractRevision.builder()
                .id("revision-1")
                .contract(contract)
                .revisionNumber(1)
                .build();
        ContractTemplateVersion templateVersion = ContractTemplateVersion.builder()
                .id("template-version-1")
                .placeholdersJson("[]")
                .build();
        ContractDocument document = ContractDocument.builder()
                .id("document-1")
                .renderFingerprint(com.hs.contract.service.ContractPreviewIdentity.fingerprint(revision, "INTERNAL"))
                .contractId("contract-1")
                .revisionId("revision-1")
                .documentType(ContractDocumentType.DOCX)
                .purpose(DocumentPurpose.PREVIEW)
                .status(DocumentGenerationStatus.READY)
                .storageObjectId("storage-1")
                .build();

        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));
        when(contractRepository.findById("contract-1")).thenReturn(Optional.of(contract));
        when(contractRepository.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));
        when(revisionRepository.findById("revision-1")).thenReturn(Optional.of(revision));
        when(templateVersionRepository.findById("template-version-1")).thenReturn(Optional.of(templateVersion));
        when(documentRepository.findByContractIdAndRevisionId("contract-1", "revision-1"))
                .thenReturn(List.of(document));
        when(contractRepository.save(contract)).thenReturn(contract);

        var response = service.sendToTenant("contract-1");

        assertEquals(ContractStatus.PENDING_REVIEW, response.getStatus());
        assertEquals(DocumentPurpose.OFFICIAL, document.getPurpose());
        verify(documentRepository).saveAll(List.of(document));
        verify(contractRepository).save(contract);
    }

    @Test
    void tenantCannotDiscoverOrOpenLandlordDraft() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        ContractServiceImpl service = createService(
                contractRepository,
                mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class),
                mock(ContractTemplateVersionRepository.class)
        );
        Contract draft = Contract.builder()
                .id("contract-1")
                .rentalRequestId("request-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.DRAFT)
                .build();

        UserContextHolder.set(new UserContext("tenant-1", "tenant@example.com"));
        when(contractRepository.findByRentalRequestId("request-1")).thenReturn(Optional.of(draft));
        when(contractRepository.findById("contract-1")).thenReturn(Optional.of(draft));
        when(contractRepository.findByIdForUpdate("contract-1")).thenReturn(Optional.of(draft));

        assertNull(service.findByRentalRequestId("request-1"));
        assertThrows(AppException.class, () -> service.getContract("contract-1"));
    }

    @Test
    void mockPaymentIncludesEstimatedChargesAndExcludesMeterCharges() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        ContractRevisionRepository revisionRepository = mock(ContractRevisionRepository.class);
        ContractServiceImpl service = createService(
                contractRepository,
                revisionRepository,
                mock(ContractDocumentRepository.class),
                mock(ContractTemplateVersionRepository.class)
        );
        Contract contract = Contract.builder()
                .id("contract-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .currentRevisionId("revision-1")
                .status(ContractStatus.PENDING_REVIEW)
                .paymentStatus(ContractPaymentStatus.UNPAID)
                .build();
        ContractRevision revision = ContractRevision.builder()
                .id("revision-1")
                .contract(contract)
                .financialSnapshot("{\"amountValue\":\"5050000\",\"depositAmountValue\":\"5050000\"}")
                .chargesSnapshot("["
                        + "{\"name\":\"Nước\",\"billingMethod\":\"PER_PERSON_MONTH\",\"estimatedMonthlyAmount\":\"300000\"},"
                        + "{\"name\":\"Điện\",\"billingMethod\":\"PER_KWH\",\"estimatedMonthlyAmount\":null}"
                        + "]")
                .build();

        UserContextHolder.set(new UserContext("tenant-1", "tenant@example.com"));
        when(contractRepository.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));
        when(revisionRepository.findById("revision-1")).thenReturn(Optional.of(revision));

        var response = service.payMock("contract-1");

        assertEquals(new BigDecimal("10400000"), response.getTotalAmount());
        assertEquals(new BigDecimal("300000"), response.getChargesTotal());
        assertEquals(List.of("Điện"), response.getExcludedMeterCharges());
        assertEquals(ContractPaymentStatus.PAID_MOCK, response.getPaymentStatus());
        assertEquals(ContractPaymentStatus.PAID_MOCK, contract.getPaymentStatus());
    }

    @Test
    void paidTenantCanSignAndCompleteRentalLifecycle() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        RentalRequestRepository rentalRequestRepository = mock(RentalRequestRepository.class);
        ListingStatusService listingStatusService = mock(ListingStatusService.class);
        ContractServiceImpl service = createService(
                contractRepository,
                mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class),
                mock(ContractTemplateVersionRepository.class),
                rentalRequestRepository,
                listingStatusService
        );
        Listing listing = Listing.builder().id("listing-1").status(ListingStatus.RESERVED).build();
        RentalRequest rentalRequest = RentalRequest.builder()
                .id("request-1")
                .listing(listing)
                .renterId("tenant-1")
                .status(RentalRequestStatus.ACCEPTED)
                .build();
        Contract contract = Contract.builder()
                .id("contract-1")
                .rentalRequestId("request-1")
                .listingId("listing-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.PENDING_REVIEW)
                .paymentStatus(ContractPaymentStatus.PAID_MOCK)
                .build();

        UserContextHolder.set(new UserContext("tenant-1", "tenant@example.com"));
        when(contractRepository.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));
        when(rentalRequestRepository.findById("request-1")).thenReturn(Optional.of(rentalRequest));
        when(contractRepository.save(contract)).thenReturn(contract);

        var response = service.sign("contract-1");

        assertEquals(ContractStatus.ACTIVE, response.getStatus());
        assertEquals(RentalRequestStatus.COMPLETED, rentalRequest.getStatus());
        verify(listingStatusService).markRentedByContract("listing-1", "tenant-1");
        verify(rentalRequestRepository).save(rentalRequest);
        verify(contractRepository).save(contract);
    }

    @Test
    void unpaidTenantCannotSignContract() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        RentalRequestRepository rentalRequestRepository = mock(RentalRequestRepository.class);
        ListingStatusService listingStatusService = mock(ListingStatusService.class);
        ContractServiceImpl service = createService(
                contractRepository,
                mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class),
                mock(ContractTemplateVersionRepository.class),
                rentalRequestRepository,
                listingStatusService
        );
        Contract contract = Contract.builder()
                .id("contract-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.PENDING_REVIEW)
                .paymentStatus(ContractPaymentStatus.UNPAID)
                .build();

        UserContextHolder.set(new UserContext("tenant-1", "tenant@example.com"));
        when(contractRepository.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));

        assertThrows(AppException.class, () -> service.sign("contract-1"));
        verify(rentalRequestRepository, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.anyString());
        verify(listingStatusService, org.mockito.Mockito.never())
                .markRentedByContract(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void createDraft_failsWhenInitialPaymentNotPaid() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        RentalRequestRepository rentalRequestRepository = mock(RentalRequestRepository.class);
        PaymentRequestRepository paymentRequestRepository = mock(PaymentRequestRepository.class);

        ContractServiceImpl service = createServiceWithPayment(
                contractRepository,
                mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class),
                mock(ContractTemplateVersionRepository.class),
                rentalRequestRepository,
                mock(ListingStatusService.class),
                paymentRequestRepository
        );

        RentalRequest req = RentalRequest.builder()
                .id("req-1")
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .status(RentalRequestStatus.ACCEPTED)
                .build();

        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));
        when(rentalRequestRepository.findById("req-1")).thenReturn(Optional.of(req));
        when(contractRepository.findByRentalRequestId("req-1")).thenReturn(Optional.empty());
        when(paymentRequestRepository.findByRentalRequestIdAndType("req-1", PaymentType.INITIAL))
                .thenReturn(Optional.empty());

        CreateContractDraftRequest draftReq = new CreateContractDraftRequest("req-1");
        AppException ex = assertThrows(AppException.class, () -> service.createDraft(draftReq));
        assertEquals(com.hs.contract.advice.ContractErrorCode.RENTAL_PAYMENT_REQUIRED_BEFORE_CONTRACT.getCode(), ex.getCode());
    }

    @Test
    void createDraft_succeedsWhenInitialPaymentIsConfirmed() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        RentalRequestRepository rentalRequestRepository = mock(RentalRequestRepository.class);
        PaymentRequestRepository paymentRequestRepository = mock(PaymentRequestRepository.class);
        ContractTemplateVersionRepository templateVersionRepository = mock(ContractTemplateVersionRepository.class);
        ContractRevisionRepository revisionRepository = mock(ContractRevisionRepository.class);
        ContractDataBuilder dataBuilder = mock(ContractDataBuilder.class);

        ContractServiceImpl service = new ContractServiceImpl(
                contractRepository,
                revisionRepository,
                mock(ContractDocumentRepository.class),
                templateVersionRepository,
                rentalRequestRepository,
                mock(ListingStatusService.class),
                dataBuilder,
                new ContractFieldCatalog(),
                mock(ContractRenderService.class),
                mock(DocumentConversionService.class),
                mock(StorageService.class),
                new ObjectMapper(),
                mock(com.hs.listing.service.ParkingReservationService.class),
                paymentRequestRepository
        );

        Listing listing = Listing.builder().id("listing-1").ownerId("landlord-1")
                .category(com.hs.listing.model.constant.ListingCategory.ROOM).build();
        RentalRequest req = RentalRequest.builder()
                .id("req-1")
                .listing(listing)
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .status(RentalRequestStatus.ACCEPTED)
                .build();

        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .rentalRequestId("req-1")
                .status(PaymentStatus.CONFIRMED)
                .confirmedAt(java.time.Instant.now())
                .totalAmount(new BigDecimal("10000000"))
                .build();

        com.hs.contract.model.ContractTemplate template = com.hs.contract.model.ContractTemplate.builder()
                .id("tpl-1")
                .name("Mẫu hợp đồng thuê chuẩn")
                .build();

        ContractTemplateVersion tplVer = ContractTemplateVersion.builder()
                .id("ver-1")
                .template(template)
                .placeholdersJson("[]")
                .build();

        when(rentalRequestRepository.findById("req-1")).thenReturn(Optional.of(req));
        when(contractRepository.findByRentalRequestId("req-1")).thenReturn(Optional.empty());
        when(paymentRequestRepository.findByRentalRequestIdAndType("req-1", PaymentType.INITIAL))
                .thenReturn(Optional.of(payment));
        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(templateVersionRepository.findCurrentPublishedSystemVersions(com.hs.listing.model.constant.ListingCategory.ROOM))
                .thenReturn(java.util.List.of(tplVer));
        when(dataBuilder.build(any(), any(), any())).thenReturn(ContractDataBuilder.ContractSnapshots.builder()
                .specialTerms("Mốc 5 ngày mở hồ sơ xử lý quá hạn, không tự chấm dứt.").build());
        when(contractRepository.save(any(Contract.class))).thenAnswer(inv -> inv.getArgument(0));
        when(revisionRepository.save(any(ContractRevision.class))).thenAnswer(inv -> {
            ContractRevision r = inv.getArgument(0);
            r.setId("rev-1");
            return r;
        });

        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));

        var response = service.createDraft(new CreateContractDraftRequest("req-1"));
        org.junit.jupiter.api.Assertions.assertNotNull(response);
        assertEquals("pay-1", response.getRentalPaymentId());
        assertEquals(ContractPaymentStatus.PAID, response.getPaymentStatus());
        ArgumentCaptor<ContractRevision> revisionCaptor = ArgumentCaptor.forClass(ContractRevision.class);
        verify(revisionRepository).save(revisionCaptor.capture());
        assertEquals("Mốc 5 ngày mở hồ sơ xử lý quá hạn, không tự chấm dứt.",
                revisionCaptor.getValue().getSpecialTerms());
    }

    @Test
    void createDraft_requiresPublishedSystemTemplateForListingCategory() {
        assertTemplateConfigurationError(0,
                com.hs.contract.advice.ContractErrorCode.SYSTEM_TEMPLATE_NOT_AVAILABLE);
    }

    @Test
    void createDraft_rejectsDuplicateActiveSystemTemplates() {
        assertTemplateConfigurationError(2,
                com.hs.contract.advice.ContractErrorCode.SYSTEM_TEMPLATE_CONFIGURATION_INVALID);
    }

    private void assertTemplateConfigurationError(long activeCount,
            com.hs.contract.advice.ContractErrorCode expectedError) {
        ContractRepository contracts = mock(ContractRepository.class);
        RentalRequestRepository requests = mock(RentalRequestRepository.class);
        PaymentRequestRepository payments = mock(PaymentRequestRepository.class);
        ContractTemplateVersionRepository versions = mock(ContractTemplateVersionRepository.class);
        ContractServiceImpl service = createServiceWithPayment(
                contracts, mock(ContractRevisionRepository.class), mock(ContractDocumentRepository.class),
                versions, requests, mock(ListingStatusService.class), payments);
        Listing listing = Listing.builder().id("listing-1")
                .category(com.hs.listing.model.constant.ListingCategory.ROOM).build();
        RentalRequest request = RentalRequest.builder().id("req-1").listing(listing)
                .ownerId("landlord-1").renterId("tenant-1")
                .status(RentalRequestStatus.ACCEPTED).build();
        PaymentRequest payment = PaymentRequest.builder().id("payment-1")
                .status(PaymentStatus.CONFIRMED).build();
        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));
        when(requests.findById("req-1")).thenReturn(Optional.of(request));
        when(contracts.findByRentalRequestId("req-1")).thenReturn(Optional.empty());
        when(payments.findByRentalRequestIdAndType("req-1", PaymentType.INITIAL))
                .thenReturn(Optional.of(payment));
        when(versions.countActiveSystemTemplates(com.hs.listing.model.constant.ListingCategory.ROOM))
                .thenReturn(activeCount);
        when(versions.findCurrentPublishedSystemVersions(com.hs.listing.model.constant.ListingCategory.ROOM))
                .thenReturn(List.of());

        AppException error = assertThrows(AppException.class,
                () -> service.createDraft(new CreateContractDraftRequest("req-1")));
        assertEquals(expectedError.getCode(), error.getCode());
    }

    @Test
    void payMock_rejectedForContractWithRentalPayment() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        ContractServiceImpl service = createService(
                contractRepository,
                mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class),
                mock(ContractTemplateVersionRepository.class)
        );

        Contract contract = Contract.builder()
                .id("contract-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.PENDING_REVIEW)
                .rentalPaymentId("pay-1") // Hợp đồng luồng mới
                .build();

        UserContextHolder.set(new UserContext("tenant-1", "tenant@example.com"));
        when(contractRepository.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));

        AppException ex = assertThrows(AppException.class, () -> service.payMock("contract-1"));
        assertEquals(com.hs.contract.advice.ContractErrorCode.CONTRACT_PAYMENT_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void sign_allowedForNewContractWhenRentalPaymentIsConfirmed() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        RentalRequestRepository rentalRequestRepository = mock(RentalRequestRepository.class);
        ListingStatusService listingStatusService = mock(ListingStatusService.class);
        PaymentRequestRepository paymentRequestRepository = mock(PaymentRequestRepository.class);

        ContractServiceImpl service = createServiceWithPayment(
                contractRepository,
                mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class),
                mock(ContractTemplateVersionRepository.class),
                rentalRequestRepository,
                listingStatusService,
                paymentRequestRepository
        );

        Listing listing = Listing.builder().id("listing-1").ownerId("landlord-1").build();
        RentalRequest rentalRequest = RentalRequest.builder()
                .id("request-1")
                .listing(listing)
                .renterId("tenant-1")
                .status(RentalRequestStatus.ACCEPTED)
                .build();

        Contract contract = Contract.builder()
                .id("contract-1")
                .rentalRequestId("request-1")
                .listingId("listing-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.PENDING_REVIEW)
                .rentalPaymentId("pay-1")
                .build();

        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .status(PaymentStatus.CONFIRMED)
                .build();

        UserContextHolder.set(new UserContext("tenant-1", "tenant@example.com"));
        when(contractRepository.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));
        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(rentalRequestRepository.findById("request-1")).thenReturn(Optional.of(rentalRequest));
        when(contractRepository.save(contract)).thenReturn(contract);

        var response = service.sign("contract-1");

        assertEquals(ContractStatus.ACTIVE, response.getStatus());
        assertEquals(RentalRequestStatus.COMPLETED, rentalRequest.getStatus());
        verify(listingStatusService).markRentedByContract("listing-1", "tenant-1");
    }

    @Test
    void createDraft_succeedsWhenInitialPaymentIsConfirmedReal() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        RentalRequestRepository rentalRequestRepository = mock(RentalRequestRepository.class);
        PaymentRequestRepository paymentRequestRepository = mock(PaymentRequestRepository.class);
        ContractTemplateVersionRepository templateVersionRepository = mock(ContractTemplateVersionRepository.class);
        ContractRevisionRepository revisionRepository = mock(ContractRevisionRepository.class);
        ContractDataBuilder dataBuilder = mock(ContractDataBuilder.class);

        ContractServiceImpl service = new ContractServiceImpl(
                contractRepository,
                revisionRepository,
                mock(ContractDocumentRepository.class),
                templateVersionRepository,
                rentalRequestRepository,
                mock(ListingStatusService.class),
                dataBuilder,
                new ContractFieldCatalog(),
                mock(ContractRenderService.class),
                mock(DocumentConversionService.class),
                mock(StorageService.class),
                new ObjectMapper(),
                mock(com.hs.listing.service.ParkingReservationService.class),
                paymentRequestRepository
        );

        Listing listing = Listing.builder().id("listing-1").ownerId("landlord-1")
                .category(com.hs.listing.model.constant.ListingCategory.ROOM).build();
        RentalRequest req = RentalRequest.builder()
                .id("req-1")
                .listing(listing)
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .status(RentalRequestStatus.ACCEPTED)
                .build();

        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-real-1")
                .rentalRequestId("req-1")
                .status(PaymentStatus.CONFIRMED)
                .confirmedAt(java.time.Instant.now())
                .totalAmount(new BigDecimal("15000000"))
                .build();

        com.hs.contract.model.ContractTemplate template = com.hs.contract.model.ContractTemplate.builder()
                .id("tpl-1")
                .name("Mẫu hợp đồng thuê chuẩn")
                .build();

        ContractTemplateVersion tplVer = ContractTemplateVersion.builder()
                .id("ver-1")
                .template(template)
                .placeholdersJson("[]")
                .build();

        when(rentalRequestRepository.findById("req-1")).thenReturn(Optional.of(req));
        when(contractRepository.findByRentalRequestId("req-1")).thenReturn(Optional.empty());
        when(paymentRequestRepository.findByRentalRequestIdAndType("req-1", PaymentType.INITIAL))
                .thenReturn(Optional.of(payment));
        when(paymentRequestRepository.findById("pay-real-1")).thenReturn(Optional.of(payment));
        when(templateVersionRepository.findCurrentPublishedSystemVersions(com.hs.listing.model.constant.ListingCategory.ROOM))
                .thenReturn(java.util.List.of(tplVer));
        when(dataBuilder.build(any(), any(), any())).thenReturn(ContractDataBuilder.ContractSnapshots.builder().build());
        when(contractRepository.save(any(Contract.class))).thenAnswer(inv -> inv.getArgument(0));
        when(revisionRepository.save(any(ContractRevision.class))).thenAnswer(inv -> {
            ContractRevision r = inv.getArgument(0);
            r.setId("rev-1");
            return r;
        });

        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));

        var response = service.createDraft(new CreateContractDraftRequest("req-1"));
        org.junit.jupiter.api.Assertions.assertNotNull(response);
        assertEquals("pay-real-1", response.getRentalPaymentId());
        assertEquals(ContractPaymentStatus.PAID, response.getPaymentStatus());
    }

    @Test
    void assessCompleteness_requiresInitialReadingsOnlyForMeteredCharges() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        ContractRevisionRepository revisionRepository = mock(ContractRevisionRepository.class);
        ContractTemplateVersionRepository templateVersionRepository = mock(ContractTemplateVersionRepository.class);
        ContractRenderService renderService = mock(ContractRenderService.class);

        ContractServiceImpl service = new ContractServiceImpl(
                contractRepository,
                revisionRepository,
                mock(ContractDocumentRepository.class),
                templateVersionRepository,
                mock(RentalRequestRepository.class),
                mock(ListingStatusService.class),
                mock(ContractDataBuilder.class),
                new ContractFieldCatalog(),
                renderService,
                mock(DocumentConversionService.class),
                mock(StorageService.class),
                new ObjectMapper(),
                null,
                null
        );

        Contract contract = Contract.builder()
                .id("contract-complete-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .templateVersionId("tpl-v1")
                .currentRevisionId("rev-1")
                .build();

        ContractRevision revision = ContractRevision.builder()
                .id("rev-1")
                .contract(contract)
                .revisionNumber(1)
                .build();

        // Word template contains meters.electricityInitial and meters.waterInitial
        ContractTemplateVersion tplVer = ContractTemplateVersion.builder()
                .id("tpl-v1")
                .placeholdersJson("[\"meters.electricityInitial\", \"meters.waterInitial\", \"contract.number\"]")
                .build();

        when(contractRepository.findById("contract-complete-1")).thenReturn(Optional.of(contract));
        when(contractRepository.findByIdForUpdate("contract-complete-1")).thenReturn(Optional.of(contract));
        when(revisionRepository.findById("rev-1")).thenReturn(Optional.of(revision));
        when(templateVersionRepository.findById("tpl-v1")).thenReturn(Optional.of(tplVer));

        java.util.Map<String, Object> dataModel = new java.util.HashMap<>();
        java.util.Map<String, Object> contractMap = new java.util.HashMap<>();
        contractMap.put("number", "HD-2026-001");
        dataModel.put("contract", contractMap);
        // meters are empty
        dataModel.put("meters", new java.util.HashMap<>());

        when(renderService.buildDataModelFromSnapshots(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(dataModel);

        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));

        var completeness = service.getCompleteness("contract-complete-1");
        org.junit.jupiter.api.Assertions.assertTrue(completeness.isComplete(), "No metered charges means readings are optional");
        org.junit.jupiter.api.Assertions.assertTrue(completeness.getMissingFields().isEmpty());

        revision.setChargesSnapshot("[{\"chargeType\":\"ELECTRICITY\",\"billingMethod\":\"PER_KWH\"},"
                + "{\"chargeType\":\"WATER\",\"billingMethod\":\"PER_M3\"}]");
        completeness = service.getCompleteness("contract-complete-1");
        org.junit.jupiter.api.Assertions.assertFalse(completeness.isComplete());
        assertEquals(java.util.Set.of("meters.electricityInitial", "meters.waterInitial"),
                completeness.getMissingFields().stream().map(field -> field.getKey()).collect(java.util.stream.Collectors.toSet()));

        java.util.Map<String, Object> meters = new java.util.HashMap<>();
        meters.put("electricityInitial", "0");
        meters.put("waterInitial", "85.5");
        dataModel.put("meters", meters);
        org.junit.jupiter.api.Assertions.assertTrue(service.getCompleteness("contract-complete-1").isComplete());

        meters.put("electricityInitial", "-1");
        org.junit.jupiter.api.Assertions.assertFalse(service.getCompleteness("contract-complete-1").isComplete());
        meters.put("electricityInitial", "1250");

        revision.setChargesSnapshot("[{\"chargeType\":\"ELECTRICITY\",\"billingMethod\":\"PER_KWH\"},"
                + "{\"chargeType\":\"WATER\",\"billingMethod\":\"PER_PERSON_MONTH\"}]");
        meters.remove("waterInitial");
        org.junit.jupiter.api.Assertions.assertTrue(service.getCompleteness("contract-complete-1").isComplete(),
                "Water billed per person does not need an initial water reading");

        tplVer.setPlaceholdersJson("[\"contract.number\"]");
        meters.remove("electricityInitial");
        completeness = service.getCompleteness("contract-complete-1");
        org.junit.jupiter.api.Assertions.assertFalse(completeness.isComplete(),
                "Metered readings are required even if the Word template omits their placeholders");
        assertEquals("meters.electricityInitial", completeness.getMissingFields().get(0).getKey());

        revision.setChargesSnapshot("[{\"chargeType\":\"WATER\",\"billingMethod\":\"STATE_WATER_RATE\"}]");
        completeness = service.getCompleteness("contract-complete-1");
        assertEquals("meters.waterInitial", completeness.getMissingFields().get(0).getKey());
    }

    @Test
    void tenantCannotSignIfDifferentTenant() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        ContractServiceImpl service = createService(
                contractRepository,
                mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class),
                mock(ContractTemplateVersionRepository.class)
        );

        Contract contract = Contract.builder()
                .id("contract-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.PENDING_REVIEW)
                .paymentStatus(ContractPaymentStatus.PAID_MOCK)
                .build();

        // User is tenant-2, not tenant-1
        UserContextHolder.set(new UserContext("tenant-2", "tenant2@example.com"));
        when(contractRepository.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));

        assertThrows(AppException.class, () -> service.sign("contract-1"));
    }

    @Test
    void cannotUpdateRevisionIfNotDraft() {
        ContractRepository contractRepository = mock(ContractRepository.class);
        ContractServiceImpl service = createService(
                contractRepository,
                mock(ContractRevisionRepository.class),
                mock(ContractDocumentRepository.class),
                mock(ContractTemplateVersionRepository.class)
        );

        Contract contract = Contract.builder()
                .id("contract-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.PENDING_REVIEW) // Not DRAFT
                .build();

        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));
        when(contractRepository.findById("contract-1")).thenReturn(Optional.of(contract));
        when(contractRepository.findByIdForUpdate("contract-1")).thenReturn(Optional.of(contract));

        assertThrows(AppException.class, () -> service.updateRevision("contract-1", new com.hs.contract.dto.request.UpdateContractRevisionRequest()));
    }

    private ContractServiceImpl createService(
            ContractRepository contractRepository,
            ContractRevisionRepository revisionRepository,
            ContractDocumentRepository documentRepository,
            ContractTemplateVersionRepository templateVersionRepository
    ) {
        return createService(
                contractRepository,
                revisionRepository,
                documentRepository,
                templateVersionRepository,
                mock(RentalRequestRepository.class),
                mock(ListingStatusService.class)
        );
    }

    private ContractServiceImpl createService(
            ContractRepository contractRepository,
            ContractRevisionRepository revisionRepository,
            ContractDocumentRepository documentRepository,
            ContractTemplateVersionRepository templateVersionRepository,
            RentalRequestRepository rentalRequestRepository,
            ListingStatusService listingStatusService
    ) {
        return createServiceWithPayment(
                contractRepository,
                revisionRepository,
                documentRepository,
                templateVersionRepository,
                rentalRequestRepository,
                listingStatusService,
                mock(PaymentRequestRepository.class)
        );
    }

    private ContractServiceImpl createServiceWithPayment(
            ContractRepository contractRepository,
            ContractRevisionRepository revisionRepository,
            ContractDocumentRepository documentRepository,
            ContractTemplateVersionRepository templateVersionRepository,
            RentalRequestRepository rentalRequestRepository,
            ListingStatusService listingStatusService,
            PaymentRequestRepository paymentRequestRepository
    ) {
        return new ContractServiceImpl(
                contractRepository,
                revisionRepository,
                documentRepository,
                templateVersionRepository,
                rentalRequestRepository,
                listingStatusService,
                mock(ContractDataBuilder.class),
                new ContractFieldCatalog(),
                mock(ContractRenderService.class),
                mock(DocumentConversionService.class),
                mock(StorageService.class),
                new ObjectMapper(),
                mock(com.hs.listing.service.ParkingReservationService.class),
                paymentRequestRepository
        );
    }
}
