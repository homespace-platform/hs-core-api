package com.hs.contract.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.request.CreateContractTemplateRequest;
import com.hs.contract.model.ContractTemplate;
import com.hs.contract.repository.ContractTemplateRepository;
import com.hs.contract.repository.ContractTemplateVersionRepository;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.ContractFieldCatalog;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.contract.service.engine.TemplateAnalysisService;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractTemplateServiceImplTest {

    private final ContractTemplateRepository templates = mock(ContractTemplateRepository.class);
    private final ContractTemplateServiceImpl service = new ContractTemplateServiceImpl(
            templates,
            mock(ContractTemplateVersionRepository.class),
            mock(RentalRequestRepository.class),
            new ContractFieldCatalog(),
            mock(TemplateAnalysisService.class),
            mock(ContractRenderService.class),
            mock(DocumentConversionService.class),
            mock(StorageService.class),
            new ObjectMapper());

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

    private CreateContractTemplateRequest request(ListingCategory category) {
        return new CreateContractTemplateRequest("Mẫu thuê nhà", null, category, "storage-1", "template.docx");
    }
}
