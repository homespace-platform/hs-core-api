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
import com.hs.contract.dto.response.TemplateValidationResult;
import com.hs.contract.model.ContractTemplate;
import com.hs.contract.model.ContractTemplateVersion;
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
import com.hs.listing.model.constant.RentalMode;
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
        ContractTemplate template = ContractTemplate.builder()
                .name(request.getName().trim())
                .description(request.getDescription())
                .category(request.getCategory())
                .rentalMode(request.getRentalMode())
                .status(ContractTemplateStatus.ACTIVE)
                .build();

        template = templateRepository.save(template);

        // Đọc và phân tích file Word từ storage
        byte[] docxBytes = downloadStorageFile(request.getStorageObjectId());
        TemplateValidationResult validation = analysisService.analyzeTemplate(new ByteArrayInputStream(docxBytes));

        String placeholdersJson = serializeJson(validation.getDetectedPlaceholders());
        String warningsJson = serializeJson(validation.getWarnings());

        ContractTemplateVersion version = ContractTemplateVersion.builder()
                .template(template)
                .versionNumber(1)
                .storageObjectId(request.getStorageObjectId())
                .originalFileName(request.getOriginalFileName() != null ? request.getOriginalFileName() : "template_v1.docx")
                .status(TemplateVersionStatus.DRAFT)
                .placeholdersJson(placeholdersJson)
                .validationErrorsJson(warningsJson)
                .build();

        versionRepository.save(version);

        log.info("Created new ContractTemplate id={}, version 1 with {} placeholders, valid={}",
                template.getId(), validation.getDetectedPlaceholders().size(), validation.isValid());

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
    public PageResponse<ContractTemplateResponse> listTemplates(
            ContractTemplateStatus status, ListingCategory category, RentalMode rentalMode, int page, int size) {
        Specification<ContractTemplate> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("active")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (category != null) {
                predicates.add(cb.or(
                        cb.isNull(root.get("category")),
                        cb.equal(root.get("category"), category)
                ));
            }
            if (rentalMode != null) {
                predicates.add(cb.or(
                        cb.isNull(root.get("rentalMode")),
                        cb.equal(root.get("rentalMode"), rentalMode)
                ));
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
    public List<ContractTemplateResponse> listTemplates(ContractTemplateStatus status, ListingCategory category, RentalMode rentalMode) {
        return listTemplates(status, category, rentalMode, 1, 1000).getResult();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractTemplateResponse> getApplicableTemplates(String rentalRequestId) {
        RentalRequest request = rentalRequestRepository.findById(rentalRequestId)
                .orElseThrow(() -> new AppException(ContractErrorCode.RENTAL_REQUEST_NOT_APPROVED));

        Listing listing = request.getListing();
        ListingCategory category = listing != null ? listing.getCategory() : null;
        RentalMode rentalMode = listing != null ? listing.getRentalMode() : null;

        List<ContractTemplate> templates = templateRepository.findApplicablePublishedTemplates(category, rentalMode);
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
        if (request.getRentalMode() != null) {
            template.setRentalMode(request.getRentalMode());
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
        TemplateValidationResult validation = analysisService.analyzeTemplate(new ByteArrayInputStream(docxBytes));

        ContractTemplateVersion version = ContractTemplateVersion.builder()
                .template(template)
                .versionNumber(nextVersion)
                .storageObjectId(request.getStorageObjectId())
                .originalFileName(request.getOriginalFileName() != null ? request.getOriginalFileName() : "template_v" + nextVersion + ".docx")
                .status(TemplateVersionStatus.DRAFT)
                .placeholdersJson(serializeJson(validation.getDetectedPlaceholders()))
                .validationErrorsJson(serializeJson(validation.getWarnings()))
                .build();

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

    private ContractTemplateResponse toTemplateResponse(ContractTemplate t, int versionsCount) {
        return ContractTemplateResponse.builder()
                .id(t.getId())
                .name(t.getName())
                .description(t.getDescription())
                .category(t.getCategory())
                .rentalMode(t.getRentalMode())
                .status(t.getStatus())
                .latestPublishedVersionId(t.getLatestPublishedVersionId())
                .versionsCount(versionsCount)
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
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
}
