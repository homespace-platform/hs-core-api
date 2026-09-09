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
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.service.ListingStatusService;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;

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
                new ObjectMapper()
        );
    }
}
