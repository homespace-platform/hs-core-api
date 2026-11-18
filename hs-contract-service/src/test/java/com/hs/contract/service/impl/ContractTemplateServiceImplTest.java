package com.hs.contract.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.request.CreateContractTemplateRequest;
import com.hs.contract.model.ContractTemplate;
import com.hs.contract.model.ContractTemplateVersion;
import com.hs.contract.model.constant.ContractTemplateSource;
import com.hs.contract.model.constant.TemplateVersionStatus;
import com.hs.contract.repository.ContractTemplateRepository;
import com.hs.contract.repository.ContractTemplateVersionRepository;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.ContractFieldCatalog;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.contract.service.engine.TemplateAnalysisService;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContractTemplateServiceImplTest {

    private final ContractTemplateRepository templates = mock(ContractTemplateRepository.class);
    private final ContractTemplateVersionRepository versions = mock(ContractTemplateVersionRepository.class);
    private final RentalRequestRepository rentals = mock(RentalRequestRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final ContractTemplateServiceImpl service = new ContractTemplateServiceImpl(
            templates,
            versions,
            rentals,
            new ContractFieldCatalog(),
            mock(TemplateAnalysisService.class),
            mock(ContractRenderService.class),
            mock(DocumentConversionService.class),
            storage,
            new ObjectMapper());

    @AfterEach
    void clearIdentity() {
        UserContextHolder.clear();
    }

    @Test
    void adminCannotCreateSecondTemplateForCategory() {
        when(templates.findSystemTemplatesByCategory(ListingCategory.ROOM))
                .thenReturn(List.of(ContractTemplate.builder().id("existing").build()));

        AppException error = assertThrows(AppException.class,
                () -> service.createTemplate(request(ListingCategory.ROOM)));
        assertEquals(ContractErrorCode.SYSTEM_TEMPLATE_ALREADY_EXISTS.getCode(), error.getCode());
    }

    @Test
    void adminCannotCreateTemplateForLegacyCategory() {
        AppException error = assertThrows(AppException.class,
                () -> service.createTemplate(request(ListingCategory.OFFICE)));
        assertEquals(ContractErrorCode.SYSTEM_TEMPLATE_CATEGORY_INVALID.getCode(), error.getCode());
    }

    @Test
    void testPreviewRejectsVersionOfAnotherTemplate() {
        var other = ContractTemplate.builder().id("other").build();
        when(versions.findById("v")).thenReturn(Optional.of(version("v", other, TemplateVersionStatus.PUBLISHED)));
        assertEquals(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND.getCode(),
                assertThrows(AppException.class, () -> service.testPreviewVersion("mine", "v")).getCode());
        verifyNoInteractions(storage);
    }

    @Test
    void landlordSeesOnlyPublishedVersionsOfSystemTemplatesButAllOfItsOwn() {
        var system = ContractTemplate.builder().id("sys").source(ContractTemplateSource.SYSTEM).build();
        var own = ContractTemplate.builder().id("own").source(ContractTemplateSource.LANDLORD).ownerUserId("landlord").build();
        when(templates.findById("sys")).thenReturn(Optional.of(system));
        when(templates.findById("own")).thenReturn(Optional.of(own));
        when(versions.findByTemplateIdOrderByVersionNumberDesc("sys")).thenReturn(List.of(
                version("draft", system, TemplateVersionStatus.DRAFT), version("pub", system, TemplateVersionStatus.PUBLISHED),
                version("old", system, TemplateVersionStatus.DEPRECATED)));
        when(versions.findByTemplateIdOrderByVersionNumberDesc("own")).thenReturn(List.of(
                version("d2", own, TemplateVersionStatus.DRAFT), version("p2", own, TemplateVersionStatus.PUBLISHED)));
        when(versions.findById("draft")).thenReturn(Optional.of(version("draft", system, TemplateVersionStatus.DRAFT)));

        assertEquals(List.of("pub"), service.getVersionsForLandlord("landlord", "sys").stream().map(v -> v.getId()).toList());
        assertEquals(1, service.getTemplateForLandlord("landlord", "sys").getVersionsCount());
        assertEquals(2, service.getVersionsForLandlord("landlord", "own").size());
        assertEquals(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND.getCode(),
                assertThrows(AppException.class, () -> service.testPreviewForLandlord("landlord", "sys", "draft")).getCode());
        verifyNoInteractions(storage);
    }

    @Test
    void applicableTemplatesRequireIdentityAndARentalParty() {
        when(rentals.findById("rental")).thenReturn(Optional.of(
                RentalRequest.builder().id("rental").ownerId("owner").renterId("renter").build()));
        assertEquals(1002, assertThrows(AppException.class, () -> service.getApplicableTemplates("rental")).getCode());
        UserContextHolder.set(new UserContext("stranger", null));
        assertEquals(ContractErrorCode.CONTRACT_FORBIDDEN.getCode(),
                assertThrows(AppException.class, () -> service.getApplicableTemplates("rental")).getCode());
        // A party passes the check and reaches the listing/category validation.
        UserContextHolder.set(new UserContext("renter", null));
        assertEquals(ContractErrorCode.SYSTEM_TEMPLATE_CATEGORY_INVALID.getCode(),
                assertThrows(AppException.class, () -> service.getApplicableTemplates("rental")).getCode());
    }

    private static ContractTemplateVersion version(String id, ContractTemplate template, TemplateVersionStatus status) {
        return ContractTemplateVersion.builder().id(id).template(template).status(status).storageObjectId("s-" + id).build();
    }

    private CreateContractTemplateRequest request(ListingCategory category) {
        return new CreateContractTemplateRequest("Mẫu thuê nhà", null, category, "storage-1", "template.docx");
    }
}
