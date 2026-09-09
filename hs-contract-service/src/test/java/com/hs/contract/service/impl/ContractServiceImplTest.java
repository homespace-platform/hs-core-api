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
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractServiceImplTest {

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
                .contractId("contract-1")
                .revisionId("revision-1")
                .documentType(ContractDocumentType.DOCX)
                .purpose(DocumentPurpose.PREVIEW)
                .status(DocumentGenerationStatus.READY)
                .storageObjectId("storage-1")
                .build();

        UserContextHolder.set(new UserContext("landlord-1", "landlord@example.com"));
        when(contractRepository.findById("contract-1")).thenReturn(Optional.of(contract));
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

        assertNull(service.findByRentalRequestId("request-1"));
        assertThrows(AppException.class, () -> service.getContract("contract-1"));
    }

    private ContractServiceImpl createService(
            ContractRepository contractRepository,
            ContractRevisionRepository revisionRepository,
            ContractDocumentRepository documentRepository,
            ContractTemplateVersionRepository templateVersionRepository
    ) {
        return new ContractServiceImpl(
                contractRepository,
                revisionRepository,
                documentRepository,
                templateVersionRepository,
                mock(RentalRequestRepository.class),
                mock(ContractDataBuilder.class),
                new ContractFieldCatalog(),
                mock(ContractRenderService.class),
                mock(DocumentConversionService.class),
                mock(StorageService.class),
                new ObjectMapper()
        );
    }
}
