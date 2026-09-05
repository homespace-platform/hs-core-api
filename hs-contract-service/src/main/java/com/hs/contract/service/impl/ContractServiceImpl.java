package com.hs.contract.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.PageResponse;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.request.CreateContractDraftRequest;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.contract.dto.response.ContractDocumentResponse;
import com.hs.contract.dto.response.ContractResponse;
import com.hs.contract.dto.response.ContractRevisionResponse;
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
import com.hs.contract.service.ContractService;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.contract.service.engine.VietnameseCurrencyTextConverter;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingCharge;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.dto.response.StorageUrlResponse;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.service.StorageService;
import com.hs.user.model.Address;
import com.hs.user.repository.AddressRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractServiceImpl implements ContractService {

    private final ContractRepository contractRepository;
    private final ContractRevisionRepository revisionRepository;
    private final ContractDocumentRepository documentRepository;
    private final ContractTemplateVersionRepository templateVersionRepository;
    private final RentalRequestRepository rentalRequestRepository;
    private final ListingRepository listingRepository;
    private final AddressRepository addressRepository;
    private final ContractRenderService renderService;
    private final DocumentConversionService conversionService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    @Transactional
    public ContractResponse createDraft(CreateContractDraftRequest request) {
        String currentUserId = getCurrentUserId();

        RentalRequest rentalRequest = rentalRequestRepository.findById(request.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.RENTAL_REQUEST_NOT_APPROVED));

        // 1. Kiểm tra trạng thái RentalRequest: phải là ACCEPTED
        if (rentalRequest.getStatus() != RentalRequestStatus.ACCEPTED) {
            throw new AppException(ContractErrorCode.RENTAL_REQUEST_NOT_APPROVED);
        }

        // 2. Kiểm tra quyền: người gọi phải là chủ nhà
        if (!rentalRequest.getOwnerId().equals(currentUserId)) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }

        // 3. Nếu đã tồn tại Contract cho RentalRequest này thì trả về luôn
        Optional<Contract> existing = contractRepository.findByRentalRequestId(rentalRequest.getId());
        if (existing.isPresent()) {
            log.info("Contract already exists for rentalRequestId={}, returning existing", rentalRequest.getId());
            return toContractResponse(existing.get());
        }

        // 4. Lấy phiên bản mẫu
        ContractTemplateVersion templateVersion = templateVersionRepository.findById(request.getTemplateVersionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND));

        Listing listing = rentalRequest.getListing();

        // 5. Sinh số hợp đồng
        String contractNumber = "HD-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        Contract contract = Contract.builder()
                .contractNumber(contractNumber)
                .rentalRequestId(rentalRequest.getId())
                .listingId(listing != null ? listing.getId() : "")
                .landlordId(rentalRequest.getOwnerId())
                .tenantId(rentalRequest.getRenterId())
                .templateId(templateVersion.getTemplate().getId())
                .templateVersionId(templateVersion.getId())
                .status(ContractStatus.DRAFT)
                .build();

        contract = contractRepository.save(contract);

        // 6. Tạo Snapshot ban đầu cho Revision 1
        Map<String, Object> landlordSnap = buildInitialLandlordSnapshot(rentalRequest);
        Map<String, Object> tenantSnap = buildInitialTenantSnapshot(rentalRequest);
        Map<String, Object> propertySnap = buildInitialPropertySnapshot(listing);
        Map<String, Object> leaseSnap = buildInitialLeaseSnapshot(rentalRequest);
        Map<String, Object> financialSnap = buildInitialFinancialSnapshot(rentalRequest);
        List<Map<String, Object>> chargesSnap = buildInitialChargesSnapshot(listing);
        List<Map<String, Object>> equipmentSnap = buildInitialEquipmentSnapshot(listing);
        Map<String, Object> metersSnap = Map.of("electricityInitial", "0", "waterInitial", "0");

        ContractRevision revision = ContractRevision.builder()
                .contract(contract)
                .revisionNumber(1)
                .templateVersionId(templateVersion.getId())
                .landlordSnapshot(toJson(landlordSnap))
                .tenantSnapshot(toJson(tenantSnap))
                .propertySnapshot(toJson(propertySnap))
                .leaseSnapshot(toJson(leaseSnap))
                .financialSnapshot(toJson(financialSnap))
                .chargesSnapshot(toJson(chargesSnap))
                .equipmentSnapshot(toJson(equipmentSnap))
                .initialMetersSnapshot(toJson(metersSnap))
                .revisionNote("Bản chụp dữ liệu khởi tạo từ yêu cầu thuê và bài đăng.")
                .build();

        revision = revisionRepository.save(revision);

        contract.setCurrentRevisionId(revision.getId());
        contract = contractRepository.save(contract);

        log.info("Created Contract id={}, contractNumber={}, revision 1", contract.getId(), contractNumber);
        return toContractResponse(contract);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractResponse getContract(String contractId) {
        Contract contract = findContractAndCheckAccess(contractId);
        return toContractResponse(contract);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ContractResponse> getContractsForCurrentUser(String userId, ContractStatus status, int page, int size) {
        String currentUserId = (userId != null && !userId.isBlank()) ? userId : getCurrentUserId();
        if (currentUserId == null || currentUserId.isBlank() || "system".equals(currentUserId)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        Specification<Contract> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.or(
                    cb.equal(root.get("landlordId"), currentUserId),
                    cb.equal(root.get("tenantId"), currentUserId)
            ));
            predicates.add(cb.isTrue(root.get("active")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var sort = Sort.by(Sort.Order.desc("createdAt"));
        var pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100), sort);
        Page<Contract> pageResult = contractRepository.findAll(spec, pageable);
        return new PageResponse<>(pageResult.map(this::toContractResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractResponse> getContractsForCurrentUser(ContractStatus status) {
        String userId = getCurrentUserId();
        List<Contract> list = (status != null)
                ? contractRepository.findByUserInvolvedAndStatus(userId, status)
                : contractRepository.findByUserInvolved(userId);

        return list.stream().map(this::toContractResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ContractResponse> getAllContractsForAdmin(ContractStatus status, String keyword, int page, int size) {
        Specification<Contract> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("active")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("contractNumber")), pattern),
                        cb.like(cb.lower(root.get("rentalRequestId")), pattern),
                        cb.like(cb.lower(root.get("listingId")), pattern),
                        cb.like(cb.lower(root.get("landlordId")), pattern),
                        cb.like(cb.lower(root.get("tenantId")), pattern)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var sort = Sort.by(Sort.Order.desc("createdAt"));
        var pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100), sort);
        Page<Contract> pageResult = contractRepository.findAll(spec, pageable);
        return new PageResponse<>(pageResult.map(this::toContractResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractResponse> getAllContractsForAdmin(ContractStatus status, String keyword) {
        return getAllContractsForAdmin(status, keyword, 1, 1000).getResult();
    }

    @Override
    @Transactional(readOnly = true)
    public ContractResponse getContractForAdmin(String contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        return toContractResponse(contract);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractRevisionResponse getLatestRevisionForAdmin(String contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (contract.getCurrentRevisionId() == null) {
            throw new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND);
        }
        ContractRevision revision = revisionRepository.findById(contract.getCurrentRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND));
        return toRevisionResponse(revision);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractDocumentResponse> getDocumentsForAdmin(String contractId) {
        return getDocumentsByContract(contractId);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractRevisionResponse getLatestRevision(String contractId) {
        Contract contract = findContractAndCheckAccess(contractId);
        if (contract.getCurrentRevisionId() == null) {
            throw new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND);
        }

        ContractRevision revision = revisionRepository.findById(contract.getCurrentRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND));

        return toRevisionResponse(revision);
    }

    @Override
    @Transactional
    public ContractRevisionResponse updateRevision(String contractId, UpdateContractRevisionRequest request) {
        Contract contract = findContractAndCheckAccess(contractId);
        String currentUserId = getCurrentUserId();

        // Chỉ chủ nhà mới được cập nhật bản nháp
        if (!contract.getLandlordId().equals(currentUserId)) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }

        if (contract.getStatus() != ContractStatus.DRAFT && contract.getStatus() != ContractStatus.PENDING_REVIEW) {
            throw new AppException(ContractErrorCode.INVALID_CONTRACT_STATUS);
        }

        int nextRev = revisionRepository.findMaxRevisionNumberByContractId(contractId) + 1;

        ContractRevision newRevision = ContractRevision.builder()
                .contract(contract)
                .revisionNumber(nextRev)
                .templateVersionId(contract.getTemplateVersionId())
                .landlordSnapshot(toJson(request.getLandlord()))
                .tenantSnapshot(toJson(request.getTenant()))
                .propertySnapshot(toJson(request.getProperty()))
                .leaseSnapshot(toJson(request.getLease()))
                .financialSnapshot(toJson(request.getFinancial()))
                .chargesSnapshot(toJson(request.getCharges()))
                .equipmentSnapshot(toJson(request.getEquipments()))
                .initialMetersSnapshot(toJson(request.getMeters()))
                .specialTerms(request.getSpecialTerms())
                .revisionNote(request.getRevisionNote() != null ? request.getRevisionNote() : "Cập nhật thỏa thuận hợp đồng (Revision " + nextRev + ")")
                .build();

        newRevision = revisionRepository.save(newRevision);

        contract.setCurrentRevisionId(newRevision.getId());
        contractRepository.save(contract);

        // Đánh dấu các tài liệu cũ là STALE
        List<ContractDocument> docs = documentRepository.findByContractIdOrderByGeneratedAtDesc(contractId);
        for (ContractDocument doc : docs) {
            if (doc.getStatus() == DocumentGenerationStatus.READY) {
                doc.setStatus(DocumentGenerationStatus.STALE);
                documentRepository.save(doc);
            }
        }

        log.info("Updated contract id={} to revision {}", contractId, nextRev);
        return toRevisionResponse(newRevision);
    }

    @Override
    @Transactional
    public ContractDocumentResponse triggerPreview(String contractId) {
        Contract contract = findContractAndCheckAccess(contractId);

        if (contract.getCurrentRevisionId() == null) {
            throw new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND);
        }

        ContractRevision revision = revisionRepository.findById(contract.getCurrentRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND));

        ContractTemplateVersion templateVersion = templateVersionRepository.findById(contract.getTemplateVersionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND));

        // Tải template Word từ storage
        byte[] templateBytes = downloadStorageFile(templateVersion.getStorageObjectId());

        // Chuẩn bị Data Model
        Map<String, Object> landlord = fromJson(revision.getLandlordSnapshot(), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> tenant = fromJson(revision.getTenantSnapshot(), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> property = fromJson(revision.getPropertySnapshot(), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> lease = fromJson(revision.getLeaseSnapshot(), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> financial = fromJson(revision.getFinancialSnapshot(), new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> charges = fromJson(revision.getChargesSnapshot(), new TypeReference<List<Map<String, Object>>>() {});
        List<Map<String, Object>> equipments = fromJson(revision.getEquipmentSnapshot(), new TypeReference<List<Map<String, Object>>>() {});
        Map<String, Object> meters = fromJson(revision.getInitialMetersSnapshot(), new TypeReference<Map<String, Object>>() {});

        Map<String, Object> dataModel = renderService.buildDataModelFromSnapshots(
                landlord, tenant, property, lease, financial, charges, equipments, meters,
                contract.getContractNumber(), LocalDate.now(), "Thành phố Hồ Chí Minh"
        );

        byte[] renderedDocx;
        try {
            renderedDocx = renderService.renderDocx(new ByteArrayInputStream(templateBytes), dataModel);
        } catch (Exception e) {
            log.error("Lỗi khi render DOCX hợp đồng id={}: {}", contractId, e.getMessage(), e);
            throw new AppException(ContractErrorCode.CONTRACT_RENDER_FAILED);
        }

        // Upload file DOCX vào storage
        String docxName = contract.getContractNumber() + "_rev" + revision.getRevisionNumber() + ".docx";
        StorageObjectResponse docxStorage = storageService.uploadDirect(
                renderedDocx,
                docxName,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                StoragePurpose.CONTRACT_DOCUMENT,
                "CONTRACT",
                contractId,
                StorageVisibility.PRIVATE
        );

        ContractDocument docxDoc = ContractDocument.builder()
                .contractId(contractId)
                .revisionId(revision.getId())
                .templateVersionId(templateVersion.getId())
                .documentType(ContractDocumentType.DOCX)
                .purpose(DocumentPurpose.PREVIEW)
                .storageObjectId(docxStorage.id())
                .fileName(docxName)
                .fileSize((long) renderedDocx.length)
                .status(DocumentGenerationStatus.READY)
                .build();
        documentRepository.save(docxDoc);

        // Thử chuyển sang PDF qua Gotenberg
        Optional<byte[]> pdfBytesOpt = conversionService.convertDocxToPdf(renderedDocx, docxName);

        if (pdfBytesOpt.isPresent()) {
            byte[] pdfBytes = pdfBytesOpt.get();
            String pdfName = contract.getContractNumber() + "_rev" + revision.getRevisionNumber() + ".pdf";

            StorageObjectResponse pdfStorage = storageService.uploadDirect(
                    pdfBytes,
                    pdfName,
                    "application/pdf",
                    StoragePurpose.CONTRACT_DOCUMENT,
                    "CONTRACT",
                    contractId,
                    StorageVisibility.PRIVATE
            );

            ContractDocument pdfDoc = ContractDocument.builder()
                    .contractId(contractId)
                    .revisionId(revision.getId())
                    .templateVersionId(templateVersion.getId())
                    .documentType(ContractDocumentType.PDF)
                    .purpose(DocumentPurpose.PREVIEW)
                    .storageObjectId(pdfStorage.id())
                    .fileName(pdfName)
                    .fileSize((long) pdfBytes.length)
                    .status(DocumentGenerationStatus.READY)
                    .build();

            pdfDoc = documentRepository.save(pdfDoc);
            log.info("Successfully generated and saved PDF document id={} for contract id={}", pdfDoc.getId(), contractId);
            return toDocumentResponse(pdfDoc);
        } else {
            log.info("PDF conversion was skipped or failed. Returning DOCX document id={}", docxDoc.getId());
            return toDocumentResponse(docxDoc);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ContractDocumentResponse getDocument(String documentId) {
        ContractDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_DOCUMENT_NOT_FOUND));

        findContractAndCheckAccess(document.getContractId());
        return toDocumentResponse(document);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractDocumentResponse> getDocumentsByContract(String contractId) {
        findContractAndCheckAccess(contractId);
        return documentRepository.findByContractIdOrderByGeneratedAtDesc(contractId).stream()
                .map(this::toDocumentResponse)
                .toList();
    }

    // --- Helpers xây dựng Snapshot ban đầu ---

    private Map<String, Object> buildInitialLandlordSnapshot(RentalRequest r) {
        Map<String, Object> map = new HashMap<>();
        map.put("fullName", "Chủ nhà (Bên A)");
        map.put("phone", "");
        map.put("email", "");
        map.put("idNumber", "");
        map.put("idIssueDate", "");
        map.put("idIssuePlace", "");
        map.put("permanentAddress", "");
        map.put("bankAccount", "");
        map.put("bankName", "");
        return map;
    }

    private Map<String, Object> buildInitialTenantSnapshot(RentalRequest r) {
        Map<String, Object> map = new HashMap<>();
        map.put("fullName", r.getRenterName() != null ? r.getRenterName() : "Người thuê (Bên B)");
        map.put("phone", r.getRenterPhone() != null ? r.getRenterPhone() : "");
        map.put("email", r.getRenterEmail() != null ? r.getRenterEmail() : "");
        map.put("occupantCount", r.getOccupantCount() != null ? r.getOccupantCount() : 1);
        map.put("idNumber", "");
        map.put("idIssueDate", "");
        map.put("idIssuePlace", "");
        map.put("permanentAddress", "");
        map.put("organizationName", "");
        map.put("representativeName", "");
        map.put("representativePosition", "");
        return map;
    }

    private Map<String, Object> buildInitialPropertySnapshot(Listing l) {
        Map<String, Object> map = new HashMap<>();
        if (l != null) {
            String fullAddress = "";
            Address addr = addressRepository.findByListingIdAndActiveTrue(l.getId()).orElse(null);
            if (addr != null && addr.getFullAddress() != null) {
                fullAddress = addr.getFullAddress();
            }
            map.put("fullAddress", fullAddress);
            map.put("areaText", l.getAreaM2() != null ? l.getAreaM2() + " m²" : "0 m²");
            map.put("propertyType", l.getCategory() != null ? l.getCategory().name() : "");
            map.put("unitNumber", "");
            map.put("floor", "");
        }
        return map;
    }

    private Map<String, Object> buildInitialLeaseSnapshot(RentalRequest r) {
        Map<String, Object> map = new HashMap<>();
        LocalDate start = r.getMoveInDate() != null ? r.getMoveInDate() : LocalDate.now();
        int months = r.getLeaseMonths() != null ? r.getLeaseMonths() : 12;
        LocalDate end = start.plusMonths(months);

        map.put("startDateText", start.format(DATE_FORMATTER));
        map.put("endDateText", end.format(DATE_FORMATTER));
        map.put("durationMonths", months);
        map.put("durationText", formatDurationText(months));
        map.put("handoverDateText", start.format(DATE_FORMATTER));
        return map;
    }

    private Map<String, Object> buildInitialFinancialSnapshot(RentalRequest r) {
        Map<String, Object> map = new HashMap<>();
        BigDecimal rent = r.getMonthlyRentPrice() != null ? r.getMonthlyRentPrice() : BigDecimal.ZERO;
        BigDecimal deposit = r.getDepositAmount() != null ? r.getDepositAmount() : rent;

        map.put("amountNumber", ContractRenderService.formatVND(rent) + "/tháng");
        map.put("amountWords", VietnameseCurrencyTextConverter.toWords(rent));
        map.put("paymentCycle", "Hàng tháng");
        map.put("paymentDueDay", "Từ ngày 01 đến ngày 05 hàng tháng");
        map.put("paymentMethod", "Chuyển khoản ngân hàng");
        map.put("depositAmountNumber", ContractRenderService.formatVND(deposit));
        map.put("depositAmountWords", VietnameseCurrencyTextConverter.toWords(deposit));
        map.put("depositDescription", "Tiền đặt cọc được bên A hoàn trả lại cho bên B sau khi hết hạn hợp đồng và bên B đã thanh toán đầy đủ các khoản chi phí liên quan.");
        return map;
    }

    private List<Map<String, Object>> buildInitialChargesSnapshot(Listing l) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (l != null && l.getCharges() != null && !l.getCharges().isEmpty()) {
            for (ListingCharge c : l.getCharges()) {
                String name = c.getCustomName() != null ? c.getCustomName() : c.getChargeType().name();
                String method;
                if (c.isIncludedInRent()) {
                    method = "Đã bao gồm trong giá thuê";
                } else if (c.getAmount() != null) {
                    method = ContractRenderService.formatVND(c.getAmount()) + (c.getUnit() != null ? " / " + c.getUnit() : "");
                } else {
                    method = c.getBillingMethod() != null ? c.getBillingMethod().name() : "Thỏa thuận";
                }
                list.add(Map.of("name", name, "amountAndMethod", method, "note", c.getDescription() != null ? c.getDescription() : "-"));
            }
        } else {
            list.add(Map.of("name", "Điện", "amountAndMethod", "Theo giá nhà nước / công tơ", "note", "Tính theo thực tế"));
            list.add(Map.of("name", "Nước", "amountAndMethod", "Theo giá nhà nước / khối", "note", "Tính theo thực tế"));
        }
        return list;
    }

    private List<Map<String, Object>> buildInitialEquipmentSnapshot(Listing l) {
        return List.of(
                Map.of("index", 1, "name", "Bàn giao nhà nguyên trạng", "quantity", "1", "condition", "Tốt, sạch sẽ")
        );
    }

    private String formatDurationText(int months) {
        if (months < 12) return months + " tháng";
        int y = months / 12;
        int m = months % 12;
        if (m == 0) return y + " năm (" + months + " tháng)";
        return y + " năm " + m + " tháng (" + months + " tháng)";
    }

    private Contract findContractAndCheckAccess(String contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));

        String userId = getCurrentUserId();
        // Cho phép landlord, tenant hoặc system
        if (!contract.getLandlordId().equals(userId) && !contract.getTenantId().equals(userId) && !"system".equals(userId)) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
        return contract;
    }

    private String getCurrentUserId() {
        return Optional.ofNullable(UserContextHolder.get())
                .map(UserContext::userId)
                .orElse("system");
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

    private ContractResponse toContractResponse(Contract c) {
        return ContractResponse.builder()
                .id(c.getId())
                .contractNumber(c.getContractNumber())
                .rentalRequestId(c.getRentalRequestId())
                .listingId(c.getListingId())
                .landlordId(c.getLandlordId())
                .tenantId(c.getTenantId())
                .templateId(c.getTemplateId())
                .templateVersionId(c.getTemplateVersionId())
                .currentRevisionId(c.getCurrentRevisionId())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private ContractRevisionResponse toRevisionResponse(ContractRevision r) {
        return ContractRevisionResponse.builder()
                .id(r.getId())
                .contractId(r.getContract().getId())
                .revisionNumber(r.getRevisionNumber())
                .templateVersionId(r.getTemplateVersionId())
                .landlord(fromJson(r.getLandlordSnapshot(), new TypeReference<Map<String, Object>>() {}))
                .tenant(fromJson(r.getTenantSnapshot(), new TypeReference<Map<String, Object>>() {}))
                .property(fromJson(r.getPropertySnapshot(), new TypeReference<Map<String, Object>>() {}))
                .lease(fromJson(r.getLeaseSnapshot(), new TypeReference<Map<String, Object>>() {}))
                .financial(fromJson(r.getFinancialSnapshot(), new TypeReference<Map<String, Object>>() {}))
                .charges(fromJson(r.getChargesSnapshot(), new TypeReference<List<Map<String, Object>>>() {}))
                .equipments(fromJson(r.getEquipmentSnapshot(), new TypeReference<List<Map<String, Object>>>() {}))
                .meters(fromJson(r.getInitialMetersSnapshot(), new TypeReference<Map<String, Object>>() {}))
                .specialTerms(r.getSpecialTerms())
                .revisionNote(r.getRevisionNote())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private ContractDocumentResponse toDocumentResponse(ContractDocument d) {
        String viewUrl = null;
        String downloadUrl = null;
        if (d.getStorageObjectId() != null && d.getStatus() == DocumentGenerationStatus.READY) {
            try {
                StorageUrlResponse v = storageService.createViewUrl(d.getStorageObjectId());
                if (v != null) viewUrl = v.url();
                StorageUrlResponse dl = storageService.createDownloadUrl(d.getStorageObjectId());
                if (dl != null) downloadUrl = dl.url();
            } catch (Exception e) {
                log.warn("Could not generate presigned URLs for document id={}: {}", d.getId(), e.getMessage());
            }
        }

        return ContractDocumentResponse.builder()
                .id(d.getId())
                .contractId(d.getContractId())
                .revisionId(d.getRevisionId())
                .templateVersionId(d.getTemplateVersionId())
                .documentType(d.getDocumentType())
                .purpose(d.getPurpose())
                .storageObjectId(d.getStorageObjectId())
                .fileName(d.getFileName())
                .fileSize(d.getFileSize())
                .status(d.getStatus())
                .errorMessage(d.getErrorMessage())
                .generatedAt(d.getGeneratedAt())
                .viewUrl(viewUrl)
                .downloadUrl(downloadUrl)
                .build();
    }

    private String toJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private <T> T fromJson(String json, TypeReference<T> ref) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, ref);
        } catch (Exception e) {
            return null;
        }
    }
}
