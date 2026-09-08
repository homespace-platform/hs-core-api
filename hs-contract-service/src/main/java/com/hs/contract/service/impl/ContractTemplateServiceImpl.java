package com.hs.contract.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.PageResponse;
import com.hs.contract.advice.ContractErrorCode;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.hs.contract.dto.catalog.TemplateFieldDefinition;
import com.hs.contract.dto.request.CreateContractTemplateRequest;
import com.hs.contract.dto.request.CreateTemplateVersionRequest;
import com.hs.contract.dto.request.UpdateContractTemplateRequest;
import com.hs.contract.dto.response.ContractTemplateResponse;
import com.hs.contract.dto.response.ContractTemplateVersionResponse;
import com.hs.contract.dto.response.TemplateFieldIssue;
import com.hs.contract.dto.response.TemplateValidationResult;
import com.hs.contract.model.ContractTemplate;
import com.hs.contract.model.ContractTemplateVersion;
import com.hs.contract.model.constant.ContractTemplateSource;
import com.hs.contract.model.constant.ContractTemplateStatus;
import com.hs.contract.model.constant.TemplateVersionStatus;
import com.hs.contract.repository.ContractTemplateRepository;
import com.hs.contract.repository.ContractTemplateVersionRepository;
import com.hs.contract.service.ContractTemplateService;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.ContractFieldCatalog;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.contract.service.engine.TemplateAnalysisService;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.dto.response.StorageUrlResponse;
import com.hs.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractTemplateServiceImpl implements ContractTemplateService {

    private final ContractTemplateRepository templateRepository;
    private final ContractTemplateVersionRepository versionRepository;
    private final RentalRequestRepository rentalRequestRepository;
    private final ContractFieldCatalog catalog;
    private final TemplateAnalysisService analysisService;
    private final ContractRenderService renderService;
    private final DocumentConversionService conversionService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public List<TemplateFieldDefinition> getCatalogFields() {
        return catalog.getAllDefinitions();
    }

    @Override
    @Transactional
    public ContractTemplateResponse createTemplate(CreateContractTemplateRequest request) {
        return createTemplateInternal(request, ContractTemplateSource.SYSTEM, null);
    }

    @Override
    @Transactional
    public ContractTemplateResponse createMyTemplate(String ownerUserId, CreateContractTemplateRequest request) {
        if (ownerUserId == null || ownerUserId.isBlank()) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
        return createTemplateInternal(request, ContractTemplateSource.LANDLORD, ownerUserId);
    }

    private ContractTemplateResponse createTemplateInternal(
            CreateContractTemplateRequest request, ContractTemplateSource source, String ownerUserId) {
        ContractTemplate template = ContractTemplate.builder()
                .name(request.getName().trim())
                .description(request.getDescription())
                .category(request.getCategory())
                .source(source)
                .ownerUserId(ownerUserId)
                .status(ContractTemplateStatus.ACTIVE)
                .build();

        template = templateRepository.save(template);

        byte[] docxBytes = downloadStorageFile(request.getStorageObjectId());
        TemplateValidationResult validation = analysisService.analyzeTemplate(
                new ByteArrayInputStream(docxBytes), template.getCategory());

        ContractTemplateVersion version = newVersion(template, 1, request.getStorageObjectId(),
                request.getOriginalFileName() != null ? request.getOriginalFileName() : "template_v1.docx",
                validation);

        versionRepository.save(version);

        log.info("Created ContractTemplate id={}, source={}, owner={}, placeholders={}, valid={}",
                template.getId(), source, ownerUserId,
                validation.getDetectedPlaceholders().size(), validation.isValid());

        return toTemplateResponse(template, 1);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractTemplateResponse getTemplate(String id) {
        ContractTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_NOT_FOUND));
        int count = versionRepository.findByTemplateIdOrderByVersionNumberDesc(id).size();
        return toTemplateResponse(template, count);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractTemplateResponse getTemplateForLandlord(String userId, String templateId) {
        ContractTemplate template = requireReadableByLandlord(userId, templateId);
        int count = versionRepository.findByTemplateIdOrderByVersionNumberDesc(templateId).size();
        return toTemplateResponse(template, count);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ContractTemplateResponse> listTemplates(
            ContractTemplateStatus status, ListingCategory category, int page, int size) {
        // Admin chỉ quản lý mẫu hệ thống (kể cả bản ghi cũ chưa có source)
        Specification<ContractTemplate> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("active")));
            predicates.add(cb.or(
                    cb.equal(root.get("source"), ContractTemplateSource.SYSTEM),
                    cb.isNull(root.get("source"))
            ));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var sort = Sort.by(Sort.Order.desc("createdAt"));
        var pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100), sort);
        Page<ContractTemplate> pageResult = templateRepository.findAll(spec, pageable);
        return new PageResponse<>(pageResult.map(t -> toTemplateResponse(t, t.getVersions().size())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractTemplateResponse> listTemplates(ContractTemplateStatus status, ListingCategory category) {
        return listTemplates(status, category, 1, 1000).getResult();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractTemplateResponse> listPublishedSystemTemplates(ListingCategory category) {
        return templateRepository.findPublishedSystemTemplates(category).stream()
                .map(t -> toTemplateResponse(t, t.getVersions().size()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ContractTemplateResponse> listMyTemplates(
            String ownerUserId, ContractTemplateStatus status, ListingCategory category, int page, int size) {
        Specification<ContractTemplate> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("active")));
            predicates.add(cb.equal(root.get("source"), ContractTemplateSource.LANDLORD));
            predicates.add(cb.equal(root.get("ownerUserId"), ownerUserId));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var sort = Sort.by(Sort.Order.desc("createdAt"));
        var pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100), sort);
        Page<ContractTemplate> pageResult = templateRepository.findAll(spec, pageable);
        return new PageResponse<>(pageResult.map(t -> toTemplateResponse(t, t.getVersions().size())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractTemplateResponse> getApplicableTemplates(String rentalRequestId) {
        RentalRequest request = rentalRequestRepository.findById(rentalRequestId)
                .orElseThrow(() -> new AppException(ContractErrorCode.RENTAL_REQUEST_NOT_APPROVED));

        Listing listing = request.getListing();
        ListingCategory category = listing != null ? listing.getCategory() : null;
        String ownerUserId = listing != null ? listing.getOwnerId() : null;

        List<ContractTemplate> templates = templateRepository.findApplicablePublishedTemplates(category, ownerUserId);
        return templates.stream()
                .map(t -> toTemplateResponse(t, t.getVersions().size()))
                .toList();
    }

    @Override
    @Transactional
    public ContractTemplateResponse updateTemplate(String id, UpdateContractTemplateRequest request) {
        ContractTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_NOT_FOUND));

        if (request.getName() != null && !request.getName().isBlank()) {
            template.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            template.setDescription(request.getDescription());
        }
        if (request.getCategory() != null) {
            template.setCategory(request.getCategory());
        }

        template = templateRepository.save(template);
        int count = versionRepository.findByTemplateIdOrderByVersionNumberDesc(id).size();
        return toTemplateResponse(template, count);
    }

    @Override
    @Transactional
    public void archiveTemplate(String id) {
        ContractTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_NOT_FOUND));
        template.setStatus(ContractTemplateStatus.ARCHIVED);
        templateRepository.save(template);
        log.info("Archived ContractTemplate id={}", id);
    }

    @Override
    @Transactional
    public ContractTemplateVersionResponse createVersion(String templateId, CreateTemplateVersionRequest request) {
        ContractTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_NOT_FOUND));

        int nextVersion = versionRepository.findMaxVersionNumberByTemplateId(templateId) + 1;

        byte[] docxBytes = downloadStorageFile(request.getStorageObjectId());
        TemplateValidationResult validation = analysisService.analyzeTemplate(
                new ByteArrayInputStream(docxBytes), template.getCategory());

        ContractTemplateVersion version = newVersion(template, nextVersion, request.getStorageObjectId(),
                request.getOriginalFileName() != null ? request.getOriginalFileName() : "template_v" + nextVersion + ".docx",
                validation);

        version = versionRepository.save(version);
        log.info("Created new version {} for ContractTemplate id={}", nextVersion, templateId);

        return toVersionResponse(version);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractTemplateVersionResponse> getVersions(String templateId) {
        return versionRepository.findByTemplateIdOrderByVersionNumberDesc(templateId).stream()
                .map(this::toVersionResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ContractTemplateVersionResponse getVersion(String templateId, String versionId) {
        ContractTemplateVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND));
        if (!version.getTemplate().getId().equals(templateId)) {
            throw new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND);
        }
        return toVersionResponse(version);
    }

    @Override
    @Transactional
    public ContractTemplateVersionResponse publishVersion(String templateId, String versionId) {
        ContractTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_NOT_FOUND));

        ContractTemplateVersion targetVersion = versionRepository.findById(versionId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND));

        if (!targetVersion.getTemplate().getId().equals(templateId)) {
            throw new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND);
        }

        // Chỉ cho xuất bản khi file Word đã hợp lệ: không còn mã sai và không thiếu trường bắt buộc
        List<String> invalidPlaceholders = deserializeJsonList(targetVersion.getInvalidPlaceholdersJson());
        List<TemplateFieldIssue> missingRequired = deserializeMissingFields(targetVersion.getMissingRequiredJson());
        List<String> legacyWarnings = deserializeJsonList(targetVersion.getValidationErrorsJson());
        boolean hasBlockingIssues = !invalidPlaceholders.isEmpty()
                || !missingRequired.isEmpty()
                || !legacyWarnings.isEmpty();
        if (hasBlockingIssues) {
            log.warn("Blocked publish of template {} version {}: invalid={}, missingRequired={}, warnings={}",
                    templateId, targetVersion.getVersionNumber(),
                    invalidPlaceholders.size(), missingRequired.size(), legacyWarnings.size());
            throw new AppException(ContractErrorCode.CONTRACT_TEMPLATE_INVALID);
        }

        // Cập nhật tất cả các version đã PUBLISHED trước đó thành DEPRECATED
        List<ContractTemplateVersion> versions = versionRepository.findByTemplateIdOrderByVersionNumberDesc(templateId);
        for (ContractTemplateVersion v : versions) {
            if (v.getStatus() == TemplateVersionStatus.PUBLISHED) {
                v.setStatus(TemplateVersionStatus.DEPRECATED);
                versionRepository.save(v);
            }
        }

        String userId = Optional.ofNullable(UserContextHolder.get()).map(UserContext::userId).orElse("system");

        targetVersion.setStatus(TemplateVersionStatus.PUBLISHED);
        targetVersion.setPublishedAt(Instant.now());
        targetVersion.setPublishedBy(userId);
        targetVersion = versionRepository.save(targetVersion);

        template.setLatestPublishedVersionId(targetVersion.getId());
        templateRepository.save(template);

        log.info("Published version {} for template id={}", targetVersion.getVersionNumber(), templateId);
        return toVersionResponse(targetVersion);
    }

    @Override
    public byte[] testPreviewVersion(String templateId, String versionId) {
        ContractTemplateVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND));

        byte[] templateDocxBytes = downloadStorageFile(version.getStorageObjectId());
        Map<String, Object> dummyData = renderService.buildDummyDataModel();

        try {
            byte[] filledDocx = renderService.renderDocx(new ByteArrayInputStream(templateDocxBytes), dummyData);
            // Thử convert sang PDF
            Optional<byte[]> pdfOpt = conversionService.convertDocxToPdf(filledDocx, "test_preview_" + version.getOriginalFileName());
            return pdfOpt.orElse(filledDocx);
        } catch (Exception e) {
            log.error("Lỗi khi render bản xem trước thử nghiệm: {}", e.getMessage(), e);
            throw new AppException(ContractErrorCode.CONTRACT_RENDER_FAILED);
        }
    }

    private byte[] downloadStorageFile(String storageObjectId) {
        try {
            return storageService.downloadDirect(storageObjectId);
        } catch (Exception e) {
            log.warn("Direct storage download failed for id={}, falling back to presigned URL: {}", storageObjectId, e.getMessage());
            StorageUrlResponse urlResponse = storageService.createDownloadUrl(storageObjectId);
            if (urlResponse == null || urlResponse.url() == null) {
                throw new AppException(ContractErrorCode.STORAGE_FILE_READ_FAILED);
            }
            byte[] bytes = restTemplate.getForObject(java.net.URI.create(urlResponse.url()), byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new AppException(ContractErrorCode.STORAGE_FILE_READ_FAILED);
            }
            return bytes;
        }
    }

    @Override
    @Transactional
    public ContractTemplateResponse updateMyTemplate(String ownerUserId, String templateId, UpdateContractTemplateRequest request) {
        requireOwnedByLandlord(ownerUserId, templateId);
        return updateTemplate(templateId, request);
    }

    @Override
    @Transactional
    public void archiveMyTemplate(String ownerUserId, String templateId) {
        requireOwnedByLandlord(ownerUserId, templateId);
        archiveTemplate(templateId);
    }

    @Override
    @Transactional
    public ContractTemplateVersionResponse createMyVersion(String ownerUserId, String templateId, CreateTemplateVersionRequest request) {
        requireOwnedByLandlord(ownerUserId, templateId);
        return createVersion(templateId, request);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractTemplateVersionResponse> getVersionsForLandlord(String userId, String templateId) {
        requireReadableByLandlord(userId, templateId);
        return getVersions(templateId);
    }

    @Override
    @Transactional
    public ContractTemplateVersionResponse publishMyVersion(String ownerUserId, String templateId, String versionId) {
        requireOwnedByLandlord(ownerUserId, templateId);
        return publishVersion(templateId, versionId);
    }

    @Override
    public byte[] testPreviewForLandlord(String userId, String templateId, String versionId) {
        requireReadableByLandlord(userId, templateId);
        return testPreviewVersion(templateId, versionId);
    }

    /** Mẫu hệ thống (hoặc bản ghi cũ) hoặc mẫu thuộc sở hữu của user. */
    private ContractTemplate requireReadableByLandlord(String userId, String templateId) {
        ContractTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_NOT_FOUND));
        if (isSystemTemplate(template)) {
            return template;
        }
        if (template.getSource() == ContractTemplateSource.LANDLORD
                && userId != null && userId.equals(template.getOwnerUserId())) {
            return template;
        }
        throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
    }

    /** Chỉ mẫu LANDLORD do chính user sở hữu mới được sửa / xuất bản. */
    private ContractTemplate requireOwnedByLandlord(String ownerUserId, String templateId) {
        ContractTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_NOT_FOUND));
        if (template.getSource() != ContractTemplateSource.LANDLORD
                || ownerUserId == null
                || !ownerUserId.equals(template.getOwnerUserId())) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
        return template;
    }

    private boolean isSystemTemplate(ContractTemplate template) {
        return template.getSource() == null || template.getSource() == ContractTemplateSource.SYSTEM;
    }

    private ContractTemplateResponse toTemplateResponse(ContractTemplate t, int versionsCount) {
        return ContractTemplateResponse.builder()
                .id(t.getId())
                .name(t.getName())
                .description(t.getDescription())
                .category(t.getCategory())
                .source(t.getSource() != null ? t.getSource() : ContractTemplateSource.SYSTEM)
                .ownerUserId(t.getOwnerUserId())
                .status(t.getStatus())
                .latestPublishedVersionId(t.getLatestPublishedVersionId())
                .versionsCount(versionsCount)
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    private ContractTemplateVersion newVersion(ContractTemplate template, int versionNumber, String storageObjectId,
                                               String originalFileName, TemplateValidationResult validation) {
        return ContractTemplateVersion.builder()
                .template(template)
                .versionNumber(versionNumber)
                .storageObjectId(storageObjectId)
                .originalFileName(originalFileName)
                .status(TemplateVersionStatus.DRAFT)
                .placeholdersJson(serializeJson(validation.getDetectedPlaceholders()))
                .validationErrorsJson(serializeJson(validation.getWarnings()))
                .invalidPlaceholdersJson(serializeJson(validation.getInvalidPlaceholders()))
                .missingRequiredJson(serializeJson(validation.getMissingRequiredFields()))
                .build();
    }

    private ContractTemplateVersionResponse toVersionResponse(ContractTemplateVersion v) {
        List<String> placeholders = deserializeJsonList(v.getPlaceholdersJson());
        List<String> warnings = deserializeJsonList(v.getValidationErrorsJson());

        return ContractTemplateVersionResponse.builder()
                .id(v.getId())
                .templateId(v.getTemplate().getId())
                .versionNumber(v.getVersionNumber())
                .storageObjectId(v.getStorageObjectId())
                .originalFileName(v.getOriginalFileName())
                .status(v.getStatus())
                .placeholders(placeholders)
                .validationWarnings(warnings)
                .invalidPlaceholders(deserializeJsonList(v.getInvalidPlaceholdersJson()))
                .missingRequiredFields(deserializeMissingFields(v.getMissingRequiredJson()))
                .publishedAt(v.getPublishedAt())
                .publishedBy(v.getPublishedBy())
                .createdAt(v.getCreatedAt())
                .build();
    }

    private String serializeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> deserializeJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<TemplateFieldIssue> deserializeMissingFields(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<TemplateFieldIssue>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
