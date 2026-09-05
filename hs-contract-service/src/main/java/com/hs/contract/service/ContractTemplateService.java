package com.hs.contract.service;

import com.hs.common.dto.PageResponse;
import com.hs.contract.dto.catalog.TemplateFieldDefinition;
import com.hs.contract.dto.request.CreateContractTemplateRequest;
import com.hs.contract.dto.request.CreateTemplateVersionRequest;
import com.hs.contract.dto.request.UpdateContractTemplateRequest;
import com.hs.contract.dto.response.ContractTemplateResponse;
import com.hs.contract.dto.response.ContractTemplateVersionResponse;
import com.hs.contract.model.constant.ContractTemplateStatus;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.RentalMode;

import java.util.List;

public interface ContractTemplateService {

    List<TemplateFieldDefinition> getCatalogFields();

    ContractTemplateResponse createTemplate(CreateContractTemplateRequest request);

    ContractTemplateResponse getTemplate(String id);

    PageResponse<ContractTemplateResponse> listTemplates(ContractTemplateStatus status, ListingCategory category, RentalMode rentalMode, int page, int size);

    List<ContractTemplateResponse> listTemplates(ContractTemplateStatus status, ListingCategory category, RentalMode rentalMode);

    List<ContractTemplateResponse> getApplicableTemplates(String rentalRequestId);

    ContractTemplateResponse updateTemplate(String id, UpdateContractTemplateRequest request);

    void archiveTemplate(String id);

    ContractTemplateVersionResponse createVersion(String templateId, CreateTemplateVersionRequest request);

    List<ContractTemplateVersionResponse> getVersions(String templateId);

    ContractTemplateVersionResponse getVersion(String templateId, String versionId);

    ContractTemplateVersionResponse publishVersion(String templateId, String versionId);

    byte[] testPreviewVersion(String templateId, String versionId);
}
