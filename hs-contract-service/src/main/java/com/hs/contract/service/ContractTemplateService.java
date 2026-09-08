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

import java.util.List;

public interface ContractTemplateService {

    List<TemplateFieldDefinition> getCatalogFields();

    /** Admin: tạo mẫu hệ thống. */
    ContractTemplateResponse createTemplate(CreateContractTemplateRequest request);

    /** Chủ nhà: tạo mẫu thuộc sở hữu của mình. */
    ContractTemplateResponse createMyTemplate(String ownerUserId, CreateContractTemplateRequest request);

    ContractTemplateResponse getTemplate(String id);

    /**
     * Chủ nhà xem chi tiết: được phép nếu là mẫu hệ thống (đã xuất bản hoặc bất kỳ ACTIVE)
     * hoặc mẫu mình sở hữu.
     */
    ContractTemplateResponse getTemplateForLandlord(String userId, String templateId);

    /** Admin: danh sách mẫu hệ thống. */
    PageResponse<ContractTemplateResponse> listTemplates(ContractTemplateStatus status, ListingCategory category, int page, int size);

    List<ContractTemplateResponse> listTemplates(ContractTemplateStatus status, ListingCategory category);

    /** Chủ nhà: danh sách mẫu hệ thống đã xuất bản (chỉ xem). */
    List<ContractTemplateResponse> listPublishedSystemTemplates(ListingCategory category);

    /** Chủ nhà: danh sách mẫu của mình. */
    PageResponse<ContractTemplateResponse> listMyTemplates(
            String ownerUserId, ContractTemplateStatus status, ListingCategory category, int page, int size);

    List<ContractTemplateResponse> getApplicableTemplates(String rentalRequestId);

    ContractTemplateResponse updateTemplate(String id, UpdateContractTemplateRequest request);

    ContractTemplateResponse updateMyTemplate(String ownerUserId, String templateId, UpdateContractTemplateRequest request);

    void archiveTemplate(String id);

    void archiveMyTemplate(String ownerUserId, String templateId);

    ContractTemplateVersionResponse createVersion(String templateId, CreateTemplateVersionRequest request);

    ContractTemplateVersionResponse createMyVersion(String ownerUserId, String templateId, CreateTemplateVersionRequest request);

    List<ContractTemplateVersionResponse> getVersions(String templateId);

    List<ContractTemplateVersionResponse> getVersionsForLandlord(String userId, String templateId);

    ContractTemplateVersionResponse getVersion(String templateId, String versionId);

    ContractTemplateVersionResponse publishVersion(String templateId, String versionId);

    ContractTemplateVersionResponse publishMyVersion(String ownerUserId, String templateId, String versionId);

    byte[] testPreviewVersion(String templateId, String versionId);

    byte[] testPreviewForLandlord(String userId, String templateId, String versionId);
}
