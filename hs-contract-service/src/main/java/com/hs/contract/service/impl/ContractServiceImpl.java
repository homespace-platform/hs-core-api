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
import com.hs.contract.dto.response.ContractCompletenessResponse;
import com.hs.contract.dto.response.ContractDocumentResponse;
import com.hs.contract.dto.response.ContractPaymentBreakdownResponse;
import com.hs.contract.dto.response.ContractResponse;
import com.hs.contract.dto.response.ContractRevisionResponse;
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
import com.hs.contract.service.ContractService;
import com.hs.contract.service.ContractDraftVisibility;
import com.hs.contract.service.LatePaymentPolicy;
import com.hs.contract.service.ContractPolicyTerms;
import com.hs.contract.service.ContractPreviewIdentity;
import com.hs.contract.service.MonthlyBillingSchedule;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.ContractDataBuilder;
import com.hs.contract.service.engine.ContractFieldCatalog;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.contract.pdf.PdfSignatureEngine;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ListingStatusService;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.dto.response.StorageUrlResponse;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.service.StorageService;
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

import com.hs.listing.service.ParkingReservationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Service
public class ContractServiceImpl implements ContractService {
    @Autowired(required=false) private com.hs.contract.service.RentalOnboardingService onboarding;
    private static final String SIGNING_CITY = "Thành phố Hồ Chí Minh";

    private final ContractRepository contractRepository;
    private final ContractRevisionRepository revisionRepository;
    private final ContractDocumentRepository documentRepository;
    private final ContractTemplateVersionRepository templateVersionRepository;
    private final RentalRequestRepository rentalRequestRepository;
    private final ListingStatusService listingStatusService;
    private final ContractDataBuilder dataBuilder;
    private final ContractFieldCatalog fieldCatalog;
    private final ContractRenderService renderService;
    private final DocumentConversionService conversionService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final ParkingReservationService parkingReservationService;
    private final PaymentRequestRepository paymentRequestRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired(required = false)
    private PdfSignatureEngine pdfSignatureEngine;

    @Autowired
    private com.hs.contract.service.engine.ContractAmendmentRenderer amendmentRenderer;
    @Autowired
    private com.hs.contract.service.ContractAmendmentService amendments;
    @Autowired
    private com.hs.contract.service.ContractTermsResolver termsResolver;
    @Autowired
    private com.hs.common.time.BillingTime billingTime;

    @Value("${homespace.signature.mode:INTERNAL}")
    private String signatureMode;

    @Autowired
    public ContractServiceImpl(
            ContractRepository contractRepository,
            ContractRevisionRepository revisionRepository,
            ContractDocumentRepository documentRepository,
            ContractTemplateVersionRepository templateVersionRepository,
            RentalRequestRepository rentalRequestRepository,
            ListingStatusService listingStatusService,
            ContractDataBuilder dataBuilder,
            ContractFieldCatalog fieldCatalog,
            ContractRenderService renderService,
            DocumentConversionService conversionService,
            StorageService storageService,
            ObjectMapper objectMapper,
            ParkingReservationService parkingReservationService,
            @Autowired(required = false) PaymentRequestRepository paymentRequestRepository) {
        this.contractRepository = contractRepository;
        this.revisionRepository = revisionRepository;
        this.documentRepository = documentRepository;
        this.templateVersionRepository = templateVersionRepository;
        this.rentalRequestRepository = rentalRequestRepository;
        this.listingStatusService = listingStatusService;
        this.dataBuilder = dataBuilder;
        this.fieldCatalog = fieldCatalog;
        this.renderService = renderService;
        this.conversionService = conversionService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.parkingReservationService = parkingReservationService;
        this.paymentRequestRepository = paymentRequestRepository;
    }

    public ContractServiceImpl(
            ContractRepository contractRepository,
            ContractRevisionRepository revisionRepository,
            ContractDocumentRepository documentRepository,
            ContractTemplateVersionRepository templateVersionRepository,
            RentalRequestRepository rentalRequestRepository,
            ListingStatusService listingStatusService,
            ContractDataBuilder dataBuilder,
            ContractFieldCatalog fieldCatalog,
            ContractRenderService renderService,
            DocumentConversionService conversionService,
            StorageService storageService,
            ObjectMapper objectMapper) {
        this(contractRepository, revisionRepository, documentRepository, templateVersionRepository,
                rentalRequestRepository, listingStatusService, dataBuilder, fieldCatalog, renderService,
                conversionService, storageService, objectMapper, null, null);
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> CHARGE_LIST_TYPE = new TypeReference<>() {};

    @Override
    @Transactional
    public ContractResponse createDraft(CreateContractDraftRequest request) {
        String currentUserId = getCurrentUserId();

        RentalRequest rentalRequest = rentalRequestRepository.findByIdForUpdate(request.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.RENTAL_REQUEST_NOT_APPROVED));

        // 1. Kiểm tra trạng thái RentalRequest: phải là ACCEPTED
        if (rentalRequest.getStatus() != RentalRequestStatus.ACCEPTED) {
            throw new AppException(ContractErrorCode.RENTAL_REQUEST_NOT_APPROVED);
        }

        // 2. Kiểm tra quyền: người gọi phải là chủ nhà
        if (!rentalRequest.getOwnerId().equals(currentUserId)) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
        if (rentalRequest.getCancellationRequestedAt()!=null) {
            throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED,"Hồ sơ đang chờ quyết định hủy; chưa thể tạo hợp đồng.");
        }

        // 3. Nếu đã tồn tại Contract cho RentalRequest này thì trả về luôn (tương thích backward)
        Optional<Contract> existing = contractRepository.findByRentalRequestId(rentalRequest.getId());
        if (existing.isPresent()) {
            log.info("Contract already exists for rentalRequestId={}, returning existing", rentalRequest.getId());
            return toContractResponse(existing.get());
        }

        // 4. GATE: Kiểm tra INITIAL_PAYMENT của request. Chỉ cho tạo hợp đồng khi đã xác nhận hoàn tất (CONFIRMED)
        PaymentRequest payment = paymentRequestRepository != null
                ? paymentRequestRepository.findByRentalRequestIdAndType(rentalRequest.getId(), PaymentType.INITIAL)
                    .filter(p->p.getStatus()==PaymentStatus.CONFIRMED)
                    .or(()->paymentRequestRepository.findByRentalRequestIdAndType(rentalRequest.getId(),PaymentType.RESERVATION_DEPOSIT)).orElse(null)
                : null;

        if (payment == null || payment.getStatus() != PaymentStatus.CONFIRMED) {
            throw new AppException(ContractErrorCode.RENTAL_PAYMENT_REQUIRED_BEFORE_CONTRACT);
        }

        Listing listing = rentalRequest.getListing();
        if (listing == null || listing.getCategory() == null) {
            throw new AppException(ContractErrorCode.SYSTEM_TEMPLATE_NOT_AVAILABLE);
        }
        if (templateVersionRepository.countActiveSystemTemplates(listing.getCategory()) > 1) {
            throw new AppException(ContractErrorCode.SYSTEM_TEMPLATE_CONFIGURATION_INVALID);
        }

        // Mẫu chỉ do admin phát hành. Client không được quyết định template/version.
        List<ContractTemplateVersion> currentVersions = templateVersionRepository
                .findCurrentPublishedSystemVersions(listing.getCategory());
        if (currentVersions.isEmpty()) {
            throw new AppException(ContractErrorCode.SYSTEM_TEMPLATE_NOT_AVAILABLE);
        }
        if (currentVersions.size() != 1) {
            throw new AppException(ContractErrorCode.SYSTEM_TEMPLATE_CONFIGURATION_INVALID);
        }
        ContractTemplateVersion templateVersion = currentVersions.get(0);

        // 6. Sinh số hợp đồng
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
                .rentalPaymentId(payment.getId())
                .paymentStatus(payment.getType()!=PaymentType.RESERVATION_DEPOSIT?ContractPaymentStatus.PAID:ContractPaymentStatus.UNPAID)
                .paidAt(payment.getType()!=PaymentType.RESERVATION_DEPOSIT?payment.getConfirmedAt():null)
                .build();

        contract = contractRepository.save(contract);

        // 6. Chụp dữ liệu thật của hai bên, tin đăng, yêu cầu thuê và khoản thanh toán ban đầu vào Revision 1
        payment.setContractId(contract.getId());
        if(paymentRequestRepository!=null)paymentRequestRepository.save(payment);
        ContractDataBuilder.ContractSnapshots snapshots = dataBuilder.build(rentalRequest, listing, payment);

        ContractRevision revision = ContractRevision.builder()
                .contract(contract)
                .revisionNumber(1)
                .templateVersionId(templateVersion.getId())
                .schemaVersion(2)
                .landlordSnapshot(toJson(snapshots.getLandlord()))
                .tenantSnapshot(toJson(snapshots.getTenant()))
                .propertySnapshot(toJson(snapshots.getProperty()))
                .leaseSnapshot(toJson(snapshots.getLease()))
                .financialSnapshot(toJson(snapshots.getFinancial()))
                .chargesSnapshot(toJson(snapshots.getCharges()))
                .equipmentSnapshot(toJson(snapshots.getEquipments()))
                .initialMetersSnapshot(toJson(snapshots.getMeters()))
                .initialPaymentSnapshot(toJson(snapshots.getInitialPayment()))
                .amenitiesSnapshot(toJson(snapshots.getAmenities()))
                .policiesSnapshot(toJson(snapshots.getPolicies()))
                .specialTerms(snapshots.getSpecialTerms())
                .revisionNote("Bản chụp dữ liệu khởi tạo từ hồ sơ hai bên, tin đăng và khoản thanh toán ban đầu.")
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
    public ContractResponse findByRentalRequestId(String rentalRequestId) {
        if (rentalRequestId == null || rentalRequestId.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Optional<Contract> existing = contractRepository.findByRentalRequestId(rentalRequestId);
        if (existing.isEmpty()) {
            return null;
        }
        Contract contract = existing.get();
        String currentUserId = getCurrentUserId();
        if (!currentUserId.equals(contract.getLandlordId())
                && !currentUserId.equals(contract.getTenantId())
                && !"system".equals(currentUserId)) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
        if (!currentUserId.equals(contract.getLandlordId()) && currentUserId.equals(contract.getTenantId())
                && contract.getStatus() == ContractStatus.DRAFT && !canTenantReviewRootDraft(contract)) {
            return null;
        }
        return toContractResponse(contract);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ContractResponse> getContractsForCurrentUser(String userId, ContractStatus status, ContractPartyRole role, boolean billableOnly, int page, int size) {
        String currentUserId = (userId != null && !userId.isBlank()) ? userId : getCurrentUserId();
        if (currentUserId == null || currentUserId.isBlank() || "system".equals(currentUserId)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        Specification<Contract> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (role == ContractPartyRole.LANDLORD) {
                predicates.add(cb.equal(root.get("landlordId"), currentUserId));
            } else if (role == ContractPartyRole.TENANT) {
                predicates.add(cb.equal(root.get("tenantId"), currentUserId));
                predicates.add(ContractDraftVisibility.tenantReadable(root,query,cb));
            } else {
                predicates.add(cb.or(
                        cb.equal(root.get("landlordId"), currentUserId),
                        cb.and(
                                cb.equal(root.get("tenantId"), currentUserId),
                                ContractDraftVisibility.tenantReadable(root,query,cb)
                        )
                ));
            }
            predicates.add(cb.isTrue(root.get("active")));
            predicates.add(cb.isNull(root.get("parentContractId")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (billableOnly) {
                var funded=query.subquery(Integer.class);
                var receipt=funded.from(PaymentRequest.class);
                funded.select(cb.literal(1)).where(cb.equal(receipt.get("rentalRequestId"),root.get("rentalRequestId")),
                    cb.equal(receipt.get("status"),PaymentStatus.CONFIRMED),
                    receipt.get("type").in(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT),
                    cb.equal(receipt.get("payerId"),root.get("tenantId")),cb.equal(receipt.get("payeeId"),root.get("landlordId")));
                predicates.add(cb.or(root.get("status").in(ContractStatus.ACTIVE,ContractStatus.EXPIRED,ContractStatus.CLOSED,ContractStatus.TERMINATED),cb.exists(funded)));
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

        return list.stream()
                .filter(contract -> contract.getParentContractId() == null)
                .filter(contract -> userId.equals(contract.getLandlordId())
                        || contract.getStatus() != ContractStatus.DRAFT || canTenantReviewRootDraft(contract))
                .map(this::toContractResponse)
                .toList();
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
        Contract contract = findContractForUpdateAndCheckAccess(contractId);
        if(contract.getParentContractId()!=null) throw new AppException(ContractErrorCode.LIFECYCLE_NOT_ALLOWED,
                "Văn bản liên kết giữ nguyên điều kiện đã thống nhất; không sửa bản nháp sau khi duyệt yêu cầu.");
        String currentUserId = getCurrentUserId();

        // Chỉ chủ nhà mới được cập nhật bản nháp
        if (!contract.getLandlordId().equals(currentUserId)) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }

        if (contract.getStatus() != ContractStatus.DRAFT) {
            throw new AppException(ContractErrorCode.INVALID_CONTRACT_STATUS);
        }

        Map<String,Object> previousPolicies=contract.getCurrentRevisionId()==null?Map.of():revisionRepository.findById(contract.getCurrentRevisionId())
                .map(r->fromJson(r.getPoliciesSnapshot(),MAP_TYPE)).orElse(Map.of());
        Map<String,Object> policies=LatePaymentPolicy.forNewAgreement(request.getPolicies(),previousPolicies);
        LatePaymentPolicy lateFee = LatePaymentPolicy.from(policies);
        if (lateFee.mode() != LatePaymentPolicy.Mode.NONE) {
            ContractTemplateVersion template = templateVersionRepository.findById(contract.getTemplateVersionId())
                    .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND));
            List<String> placeholders = fromJson(template.getPlaceholdersJson(), new TypeReference<List<String>>() {});
            if (placeholders == null || placeholders.stream().map(ContractFieldCatalog::normalizeKey)
                    .noneMatch("contract.specialTerms"::equals))
                throw new AppException(ContractErrorCode.CONTRACT_LATE_FEE_TEMPLATE_REQUIRED);
        }

        int nextRev = revisionRepository.findMaxRevisionNumberByContractId(contractId) + 1;

        // An unsigned draft may predate the lease-anchored billing schedule.
        // Move it to the new rule when the landlord creates its next revision;
        // signed revisions are immutable and retain their original due date.
        Map<String, Object> financial = request.getFinancial() == null ? null
                : new LinkedHashMap<>(request.getFinancial());
        if (financial != null) {
            financial.put("paymentDueDay", MonthlyBillingSchedule.paymentDueDescription());
            financial.put("paymentDueOffsetDays", MonthlyBillingSchedule.PAYMENT_WINDOW_DAYS);
        }
        if (policies != null) {
            policies.put("paymentDueDay", MonthlyBillingSchedule.paymentDueDescription());
            // A removed clause cannot remain enabled through an old structured draft flag.
            if (request.getSpecialTerms() == null || !request.getSpecialTerms()
                    .contains("bên cho thuê vẫn có thể thực hiện quyền chấm dứt đã thỏa thuận"))
                policies.put("overdueLandlordTerminationAfterFiveDays", false);
        }

        ContractRevision newRevision = ContractRevision.builder()
                .contract(contract)
                .revisionNumber(nextRev)
                .templateVersionId(contract.getTemplateVersionId())
                .schemaVersion(2)
                .landlordSnapshot(toJson(request.getLandlord()))
                .tenantSnapshot(toJson(request.getTenant()))
                .propertySnapshot(toJson(request.getProperty()))
                .leaseSnapshot(toJson(request.getLease()))
                .financialSnapshot(toJson(financial))
                .chargesSnapshot(toJson(request.getCharges()))
                .equipmentSnapshot(toJson(request.getEquipments()))
                .initialMetersSnapshot(toJson(request.getMeters()))
                .initialPaymentSnapshot(toJson(request.getInitialPayment()))
                .amenitiesSnapshot(toJson(request.getAmenities()))
                .policiesSnapshot(toJson(policies))
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
    @Transactional(readOnly = true)
    public ContractCompletenessResponse getCompleteness(String contractId) {
        Contract contract = findContractAndCheckAccess(contractId);
        ContractRevision revision = requireCurrentRevision(contract);

        return assessCompleteness(contract, revision);
    }

    private ContractCompletenessResponse assessCompleteness(Contract contract, ContractRevision revision) {

        ContractTemplateVersion templateVersion = templateVersionRepository.findById(contract.getTemplateVersionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND));

        List<String> placeholders = fromJson(templateVersion.getPlaceholdersJson(), new TypeReference<List<String>>() {});
        if (placeholders == null) {
            placeholders = List.of();
        }

        Map<String, Object> dataModel = buildDataModel(contract, revision);
        List<Map<String, Object>> charges = fromJson(revision.getChargesSnapshot(), CHARGE_LIST_TYPE);
        boolean electricityMetered = hasMeteredCharge(charges, "ELECTRICITY", Set.of("PER_KWH"));
        boolean waterMetered = hasMeteredCharge(charges, "WATER", Set.of("PER_M3", "STATE_WATER_RATE"));

        List<ContractCompletenessResponse.MissingField> missing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> dynamicTables = new HashSet<>();
        Set<String> seen = new HashSet<>();
        int total = 0;
        int filled = 0;

        ListingCategory category = templateVersion.getTemplate() != null ? templateVersion.getTemplate().getCategory() : null;
        if (category == null && dataModel.get("property") instanceof Map<?, ?> propMap) {
            Object catObj = propMap.get("category");
            if (catObj instanceof ListingCategory lc) {
                category = lc;
            } else if (catObj instanceof String catStr && !catStr.isBlank()) {
                try {
                    category = ListingCategory.valueOf(catStr);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        for (String raw : placeholders) {
            String key = ContractFieldCatalog.normalizeKey(raw);
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            if (key.startsWith("#")) {
                dynamicTables.add(key);
                continue;
            }

            total++;
            Object value = ContractRenderService.resolvePath(dataModel, key);
            boolean isRequired = fieldCatalog.isRequiredFor(key, category)
                    && !"landlord.idIssueDate".equals(key)
                    && !"landlord.idIssuePlace".equals(key)
                    && !"tenant.idIssueDate".equals(key)
                    && !"tenant.idIssuePlace".equals(key)
                    && !"lease.rentalMode".equals(key)
                    && !"tenant.organizationName".equals(key)
                    && !"tenant.representativeName".equals(key)
                    && !"tenant.representativePosition".equals(key)
                    && !"contract.specialTerms".equals(key)
                    && !"contract.revisionNumber".equals(key)
                    && !"contract.schemaVersion".equals(key)
                    && !key.startsWith("meters.")
                    && !key.startsWith("payment.initial.");

            if ("meters.electricityInitial".equals(key)) {
                isRequired = electricityMetered;
            } else if ("meters.waterInitial".equals(key)) {
                isRequired = waterMetered;
            }

            if (isRequired && key.startsWith("meters.") && !isValidMeterReading(value)) {
                missing.add(toMissingField(key));
            } else if (value != null && !String.valueOf(value).isBlank()) {
                filled++;
            } else if (!isRequired) {
                // Trường tùy chọn hoặc không bắt buộc cho loại hình này - không chặn hoàn tất hợp đồng
                filled++;
            } else {
                missing.add(toMissingField(key));
            }
        }

        for (String meterKey : List.of("meters.electricityInitial", "meters.waterInitial")) {
            boolean required = "meters.electricityInitial".equals(meterKey) ? electricityMetered : waterMetered;
            if (required && !seen.contains(meterKey)) {
                total++;
                if (isValidMeterReading(ContractRenderService.resolvePath(dataModel, meterKey))) {
                    filled++;
                } else {
                    missing.add(toMissingField(meterKey));
                }
            }
        }

        if (!seen.contains("contract.specialTerms")
                && (!ContractPolicyTerms.clause(fromJson(revision.getPoliciesSnapshot(), MAP_TYPE)).isBlank()
                || !LatePaymentPolicy.from(fromJson(revision.getPoliciesSnapshot(), MAP_TYPE)).clause().isBlank()
                || (revision.getSpecialTerms() != null && !revision.getSpecialTerms().isBlank()))) {
            total++;
            missing.add(toMissingField("contract.specialTerms"));
        }

        if (dynamicTables.contains("#chargesTable")
                && isEmptyList(fromJson(revision.getChargesSnapshot(), CHARGE_LIST_TYPE))) {
            warnings.add("Tin đăng chưa khai báo khoản phí dịch vụ nào nên bảng biểu phí trong hợp đồng sẽ để trống.");
        }
        if (dynamicTables.contains("#equipmentTable")
                && isEmptyList(fromJson(revision.getEquipmentSnapshot(), CHARGE_LIST_TYPE))) {
            warnings.add("Tin đăng chưa khai báo nội thất bàn giao nên biên bản trang thiết bị sẽ để trống.");
        }
        if (dynamicTables.contains("#amenitiesTable")
                && isEmptyList(fromJson(revision.getAmenitiesSnapshot(), CHARGE_LIST_TYPE))) {
            warnings.add("Tin đăng chưa khai báo tiện ích nên bảng tiện ích trong hợp đồng sẽ để trống.");
        }
        Map<String,Object> listingPolicySnapshot = fromJson(revision.getPoliciesSnapshot(),MAP_TYPE);
        if (!dynamicTables.contains("#policiesTable") && listingPolicySnapshot != null && listingPolicySnapshot.get("listingPolicies") instanceof List<?> rows && !rows.isEmpty())
            warnings.add("Mẫu Word chưa có {{#policiesTable}}. Chính sách được in trong khối thỏa thuận để không mất dữ liệu; cần cập nhật Điều chính sách và bỏ câu mặc định trái Listing.");

        return ContractCompletenessResponse.builder()
                .contractId(contract.getId())
                .revisionId(revision.getId())
                .complete(missing.isEmpty())
                .totalFields(total)
                .filledFields(filled)
                .missingFields(missing)
                .warnings(warnings)
                .build();
    }

    private ContractCompletenessResponse.MissingField toMissingField(String key) {
        var definition = fieldCatalog.getDefinition(key).orElse(null);
        return ContractCompletenessResponse.MissingField.builder()
                .key(key)
                .label(definition != null ? definition.getLabel() : key)
                .group(definition != null ? definition.getGroup() : "Khác")
                .section(sectionOf(key))
                .build();
    }

    private static boolean hasMeteredCharge(List<Map<String, Object>> charges, String chargeType, Set<String> methods) {
        if (charges == null) return false;
        return charges.stream().filter(Objects::nonNull).anyMatch(charge -> chargeType.equals(String.valueOf(charge.get("chargeType")))
                && methods.contains(String.valueOf(charge.get("billingMethod"))));
    }

    private static boolean isValidMeterReading(Object value) {
        if (value == null) return false;
        String reading = String.valueOf(value).trim();
        return reading.length() <= 20 && reading.matches("\\d+(\\.\\d+)?");
    }

    /** Ánh xạ mã trường về đúng nhóm snapshot mà frontend cần mở ra để sửa. */
    private static String sectionOf(String key) {
        if (key.startsWith("landlord.")) return "landlord";
        if (key.startsWith("tenant.")) return "tenant";
        if (key.startsWith("property.")) return "property";
        if (key.startsWith("lease.")) return "lease";
        if (key.startsWith("rent.") || key.startsWith("deposit.")) return "financial";
        if (key.startsWith("meters.")) return "meters";
        // contract.number / signingDate / signingCity do hệ thống tự sinh lúc render.
        return "system";
    }

    @Override
    @Transactional
    public ContractDocumentResponse triggerPreview(String contractId) {
        // Database lock serializes duplicate clicks across Core instances and revision edits.
        Contract contract = findContractForUpdateAndCheckAccess(contractId);
        requireLandlord(contract);
        if (contract.getStatus() != ContractStatus.DRAFT) {
            throw new AppException(ContractErrorCode.INVALID_CONTRACT_STATUS);
        }
        ContractRevision revision = requireCurrentRevision(contract);
        if(contract.getParentContractId()==null && onboarding!=null) {
            var rental=rentalRequestRepository.findById(contract.getRentalRequestId()).orElseThrow();
            var payment=paymentRequestRepository.findByRentalRequestIdAndType(rental.getId(),PaymentType.INITIAL).orElse(null);
            revision.setInitialPaymentSnapshot(toJson(dataBuilder.onboardingPayment(rental,payment,Map.of(),List.of())));
            revisionRepository.save(revision);
        }

        ContractCompletenessResponse completeness = assessCompleteness(contract, revision);
        if (!completeness.isComplete()) {
            throw new AppException(ContractErrorCode.CONTRACT_DATA_INCOMPLETE);
        }

        String fingerprint = ContractPreviewIdentity.fingerprint(revision, signatureMode);
        Optional<ContractDocument> existing = currentPreview(contractId, revision, fingerprint);
        if (existing.isPresent()) return toDocumentResponse(existing.get());

        ContractTemplateVersion templateVersion = templateVersionRepository.findById(contract.getTemplateVersionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_TEMPLATE_VERSION_NOT_FOUND));

        Map<String, Object> dataModel = buildDataModel(contract, revision);

        byte[] renderedDocx;
        try {
            if ("AMENDMENT".equals(contract.getAgreementKind())) amendments.validateSigning(contract);
            byte[] templateBytes = downloadStorageFile(templateVersion.getStorageObjectId());
            renderedDocx = renderService.renderDocx(new ByteArrayInputStream(templateBytes), dataModel);
            if ("AMENDMENT".equals(contract.getAgreementKind())) renderedDocx = amendmentRenderer.appendAdjustment(renderedDocx, contract);
        } catch (Exception e) {
            log.error("Lỗi khi render DOCX hợp đồng id={}: {}", contractId, e.getMessage(), e);
            throw new AppException(ContractErrorCode.CONTRACT_RENDER_FAILED);
        }

        String docxName = contract.getContractNumber() + "_rev" + revision.getRevisionNumber() + ".docx";
        // Complete conversion/signature preparation before uploading. A real PDF failure
        // must not leave a ready DOCX (or an orphan upload) in the SmartCA workflow.
        Optional<byte[]> pdfBytesOpt = conversionService.convertDocxToPdf(renderedDocx, docxName);
        if ("SMARTCA".equalsIgnoreCase(signatureMode)) {
            if (pdfBytesOpt.isEmpty() || pdfSignatureEngine == null) {
                throw new AppException(ContractErrorCode.SIGNATURE_PDF_NOT_READY,
                        "Không thể chuyển Word sang PDF; cần PDF trước khi ký số.");
            }
            try {
                pdfBytesOpt = Optional.of(pdfSignatureEngine.addSignaturePage(pdfBytesOpt.get()));
            } catch (Exception e) {
                log.error("Unable to prepare signature page for contract {}", contractId, e);
                throw new AppException(ContractErrorCode.SIGNATURE_PDF_NOT_READY);
            }
        }

        // Upload file DOCX vào storage
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
                .renderFingerprint(fingerprint)
                .purpose(DocumentPurpose.PREVIEW)
                .storageObjectId(docxStorage.id())
                .fileName(docxName)
                .fileSize((long) renderedDocx.length)
                .status(DocumentGenerationStatus.READY)
                .generatedAt(java.time.Instant.now())
                .build();
        documentRepository.save(docxDoc);

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
                    .renderFingerprint(fingerprint)
                    .purpose(DocumentPurpose.PREVIEW)
                    .storageObjectId(pdfStorage.id())
                    .fileName(pdfName)
                    .fileSize((long) pdfBytes.length)
                    .status(DocumentGenerationStatus.READY)
                    .generatedAt(java.time.Instant.now())
                    .build();

            pdfDoc = documentRepository.save(pdfDoc);
            stalePreviousPreviews(contractId, revision.getId(), fingerprint);
            log.info("Successfully generated and saved PDF document id={} for contract id={}", pdfDoc.getId(), contractId);
            return toDocumentResponse(pdfDoc);
        } else {
            log.info("PDF conversion was skipped or failed. Returning DOCX document id={}", docxDoc.getId());
            stalePreviousPreviews(contractId, revision.getId(), fingerprint);
            return toDocumentResponse(docxDoc);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ContractDocumentResponse getPreviewStatus(String contractId) {
        Contract contract = findContractAndCheckAccess(contractId);
        ContractRevision revision = requireCurrentRevision(contract);
        return currentPreview(contractId, revision, ContractPreviewIdentity.fingerprint(revision, signatureMode))
                .map(this::toDocumentResponse).orElse(null);
    }

    private Optional<ContractDocument> currentPreview(String contractId, ContractRevision revision, String fingerprint) {
        return documentRepository.findByContractIdAndRevisionId(contractId, revision.getId()).stream()
                .filter(doc -> doc.getStatus() == DocumentGenerationStatus.READY
                        && doc.getStorageObjectId() != null && !doc.getStorageObjectId().isBlank())
                .filter(doc -> doc.getPurpose() == DocumentPurpose.PREVIEW || doc.getPurpose() == DocumentPurpose.OFFICIAL)
                .filter(doc -> fingerprint.equals(doc.getRenderFingerprint()))
                .filter(doc -> !"SMARTCA".equalsIgnoreCase(signatureMode) || doc.getDocumentType() == ContractDocumentType.PDF)
                .sorted(Comparator.comparingInt(doc -> doc.getDocumentType() == ContractDocumentType.PDF ? 0 : 1))
                .findFirst();
    }

    private void stalePreviousPreviews(String contractId, String revisionId, String fingerprint) {
        for (ContractDocument doc : documentRepository.findByContractIdAndRevisionId(contractId, revisionId)) {
            if (doc.getPurpose() == DocumentPurpose.PREVIEW && doc.getStatus() == DocumentGenerationStatus.READY
                    && !fingerprint.equals(doc.getRenderFingerprint())) {
                doc.setStatus(DocumentGenerationStatus.STALE);
                documentRepository.save(doc);
            }
        }
    }

    @Override
    @Transactional
    public ContractResponse sendToTenant(String contractId) {
        if ("SMARTCA".equalsIgnoreCase(signatureMode)) {
            throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED,
                    "Trong chế độ SmartCA, chủ nhà phải ký số trước khi hợp đồng được gửi cho người thuê.");
        }
        Contract contract = findContractForUpdateAndCheckAccess(contractId);
        requireLandlord(contract);
        if (contract.getStatus() != ContractStatus.DRAFT) {
            throw new AppException(ContractErrorCode.INVALID_CONTRACT_STATUS);
        }

        ContractRevision revision = requireCurrentRevision(contract);
        ContractCompletenessResponse completeness = assessCompleteness(contract, revision);
        if (!completeness.isComplete()) {
            throw new AppException(ContractErrorCode.CONTRACT_DATA_INCOMPLETE);
        }

        String fingerprint = ContractPreviewIdentity.fingerprint(revision, signatureMode);
        if (currentPreview(contractId, revision, fingerprint).isEmpty()) {
            triggerPreview(contractId);
        }
        List<ContractDocument> readyDocuments = readyDocumentsForRevision(contractId, revision.getId()).stream()
                .filter(doc -> fingerprint.equals(doc.getRenderFingerprint()))
                .filter(doc -> doc.getPurpose() == DocumentPurpose.PREVIEW || doc.getPurpose() == DocumentPurpose.OFFICIAL)
                .toList();
        if (readyDocuments.isEmpty()) {
            throw new AppException(ContractErrorCode.CONTRACT_DOCUMENT_REQUIRED);
        }

        readyDocuments.forEach(document -> document.setPurpose(DocumentPurpose.OFFICIAL));
        documentRepository.saveAll(readyDocuments);

        contract.setStatus(ContractStatus.PENDING_REVIEW);
        contract.setLandlordConfirmedAt(Instant.now());
        contract = contractRepository.save(contract);
        log.info("Sent contract id={} revision={} to tenant id={}", contractId, revision.getRevisionNumber(), contract.getTenantId());
        return toContractResponse(contract);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractPaymentBreakdownResponse getPaymentBreakdown(String contractId) {
        Contract contract = findContractAndCheckAccess(contractId);
        return buildPaymentBreakdown(contract, requireCurrentRevision(contract));
    }

    @Override
    @Transactional
    public ContractPaymentBreakdownResponse payMock(String contractId) {
        Contract contract = findContractForUpdateAndCheckAccess(contractId);
        requireTenant(contract);

        // Với Contract thuộc luồng mới (đã liên kết RentalPayment), từ chối thanh toán qua endpoint hợp đồng cũ
        if (contract.getRentalPaymentId() != null) {
            throw new AppException(ContractErrorCode.CONTRACT_PAYMENT_NOT_ALLOWED);
        }

        // Nhánh tương thích legacy cho hợp đồng cũ
        if (contract.getStatus() != ContractStatus.PENDING_REVIEW
                || paymentStatusOf(contract) != ContractPaymentStatus.UNPAID) {
            throw new AppException(ContractErrorCode.CONTRACT_PAYMENT_NOT_ALLOWED);
        }

        ContractPaymentBreakdownResponse breakdown =
                buildPaymentBreakdown(contract, requireCurrentRevision(contract));
        Instant paidAt = Instant.now();
        contract.setPaymentStatus(ContractPaymentStatus.PAID_MOCK);
        contract.setPaidAt(paidAt);
        contractRepository.save(contract);

        breakdown.setPaymentStatus(ContractPaymentStatus.PAID_MOCK);
        breakdown.setPaidAt(paidAt);
        log.info("Mock payment completed for legacy contract id={} tenant id={} amount={}",
                contractId, contract.getTenantId(), breakdown.getTotalAmount());
        return breakdown;
    }

    @Override
    @Transactional
    public ContractResponse sign(String contractId) {
        if ("SMARTCA".equalsIgnoreCase(signatureMode)) {
            throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED,
                    "Hãy ký trên ứng dụng VNPT SmartCA thay vì xác nhận ký nội bộ.");
        }
        Contract contract = findContractForUpdateAndCheckAccess(contractId);
        if(contract.getParentContractId()!=null) throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED,
                "Văn bản gia hạn cần chữ ký SmartCA hợp lệ của cả hai bên.");
        requireTenant(contract);
        if (contract.getStatus() != ContractStatus.PENDING_REVIEW) {
            throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED);
        }
        // The signing window (start date reached, both payments confirmed, deadline) is decided by the backend.
        if (contract.getParentContractId() == null && onboarding != null) {
            onboarding.validateSigning(contract.getRentalRequestId(), contract.getTenantId());
        }

        // Kiểm tra thanh toán:
        // Luồng mới: Kiểm tra PaymentRequest liên kết đã CONFIRMED
        if (contract.getRentalPaymentId() != null) {
            PaymentRequest payment = paymentRequestRepository != null
                    ? paymentRequestRepository.findById(contract.getRentalPaymentId()).orElse(null)
                    : null;
            if (payment == null || payment.getStatus() != PaymentStatus.CONFIRMED) {
                throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED);
            }
        } else {
            // Nhánh tương thích legacy cho hợp đồng cũ:
            if (paymentStatusOf(contract) != ContractPaymentStatus.PAID && paymentStatusOf(contract) != ContractPaymentStatus.PAID_MOCK) {
                throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED);
            }
        }

        RentalRequest rentalRequest = rentalRequestRepository.findById(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.RENTAL_REQUEST_NOT_APPROVED));
        if (rentalRequest.getStatus() != RentalRequestStatus.ACCEPTED
                || !contract.getTenantId().equals(rentalRequest.getRenterId())
                || !contract.getListingId().equals(rentalRequest.getListing().getId())) {
            throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED);
        }

        listingStatusService.markRentedByContract(contract.getListingId(), contract.getTenantId());
        rentalRequest.setStatus(RentalRequestStatus.COMPLETED);
        rentalRequest.setHoldExpiresAt(null);
        rentalRequestRepository.save(rentalRequest);

        contract.setStatus(ContractStatus.ACTIVE);
        contract.setSignedAt(Instant.now());
        contract = contractRepository.save(contract);

        if (parkingReservationService != null) {
            parkingReservationService.activateReservationsForRequest(rentalRequest.getId(), contract.getId());
        }

        log.info("Contract id={} signed by tenant id={}; listing id={} is now rented",
                contractId, contract.getTenantId(), contract.getListingId());
        return toContractResponse(contract);
    }

    private ContractPaymentBreakdownResponse buildPaymentBreakdown(
            Contract contract,
            ContractRevision revision
    ) {
        Map<String, Object> financial = fromJson(revision.getFinancialSnapshot(), MAP_TYPE);
        if (financial == null) {
            throw new AppException(ContractErrorCode.CONTRACT_PAYMENT_DATA_INVALID);
        }

        BigDecimal monthlyRent = requiredMoney(financial, "amountValue", "amountNumber");
        BigDecimal deposit = requiredMoney(financial, "depositAmountValue", "depositAmountNumber");
        List<Map<String, Object>> snapshotCharges = fromJson(revision.getChargesSnapshot(), CHARGE_LIST_TYPE);
        List<ContractPaymentBreakdownResponse.ChargeItem> includedCharges = new ArrayList<>();
        List<String> excludedMeterCharges = new ArrayList<>();
        BigDecimal chargesTotal = BigDecimal.ZERO;

        for (Map<String, Object> charge : snapshotCharges != null ? snapshotCharges : List.<Map<String, Object>>of()) {
            String name = Optional.ofNullable(charge.get("name"))
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .orElse("Phí dịch vụ");
            String billingMethod = String.valueOf(charge.get("billingMethod"));
            if ("PER_KWH".equals(billingMethod) || "PER_M3".equals(billingMethod)
                    || "STATE_WATER_RATE".equals(billingMethod) || "PER_HOUR".equals(billingMethod)
                    || "NEGOTIABLE".equals(billingMethod) || "CUSTOM".equals(billingMethod)
                    || "NOT_APPLICABLE".equals(billingMethod) || "PER_M2_MONTH".equals(billingMethod)) {
                excludedMeterCharges.add(name);
                continue;
            }

            Object estimated = charge.get("estimatedMonthlyAmount");
            if (estimated == null || String.valueOf(estimated).isBlank()) {
                continue;
            }
            BigDecimal amount = parseDecimal(estimated);
            if (amount.signum() == 0) {
                continue;
            }
            includedCharges.add(ContractPaymentBreakdownResponse.ChargeItem.builder()
                    .name(name)
                    .amount(amount)
                    .build());
            chargesTotal = chargesTotal.add(amount);
        }

        return ContractPaymentBreakdownResponse.builder()
                .contractId(contract.getId())
                .monthlyRent(monthlyRent)
                .deposit(deposit)
                .charges(includedCharges)
                .chargesTotal(chargesTotal)
                .totalAmount(monthlyRent.add(deposit).add(
                        contract.getRentalPaymentId() == null ? chargesTotal : BigDecimal.ZERO))
                .excludedMeterCharges(excludedMeterCharges)
                .paymentStatus(paymentStatusOf(contract))
                .paidAt(contract.getPaidAt())
                .build();
    }

    private BigDecimal requiredMoney(Map<String, Object> financial, String rawKey, String displayKey) {
        Object rawValue = financial.get(rawKey);
        if (rawValue != null && !String.valueOf(rawValue).isBlank()) {
            return parseDecimal(rawValue);
        }

        Object displayValue = financial.get(displayKey);
        if (displayValue == null) {
            throw new AppException(ContractErrorCode.CONTRACT_PAYMENT_DATA_INVALID);
        }
        String digits = String.valueOf(displayValue).replaceAll("[^0-9-]", "");
        if (digits.isBlank() || "-".equals(digits)) {
            throw new AppException(ContractErrorCode.CONTRACT_PAYMENT_DATA_INVALID);
        }
        return parseDecimal(digits);
    }

    private BigDecimal parseDecimal(Object value) {
        try {
            BigDecimal amount = new BigDecimal(String.valueOf(value).trim());
            if (amount.signum() < 0) {
                throw new NumberFormatException("negative amount");
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw new AppException(ContractErrorCode.CONTRACT_PAYMENT_DATA_INVALID);
        }
    }

    private ContractPaymentStatus paymentStatusOf(Contract contract) {
        if (contract.getRentalPaymentId() != null && paymentRequestRepository != null) {
            Optional<PaymentRequest> paymentOpt = paymentRequestRepository.findById(contract.getRentalPaymentId());
            if (paymentOpt.isPresent()) {
                PaymentStatus status = paymentOpt.get().getStatus();
                if (status == PaymentStatus.CONFIRMED) {
                    return paymentOpt.get().getType()==PaymentType.RESERVATION_DEPOSIT
                            ? ContractPaymentStatus.UNPAID : ContractPaymentStatus.PAID;
                } else if (status == PaymentStatus.CANCELLED || status == PaymentStatus.EXPIRED) {
                    return ContractPaymentStatus.FAILED;
                } else {
                    return ContractPaymentStatus.UNPAID;
                }
            }
        }
        return contract.getPaymentStatus() != null
                ? contract.getPaymentStatus()
                : ContractPaymentStatus.UNPAID;
    }

    private List<ContractDocument> readyDocumentsForRevision(String contractId, String revisionId) {
        return documentRepository.findByContractIdAndRevisionId(contractId, revisionId).stream()
                .filter(document -> document.getStatus() == DocumentGenerationStatus.READY)
                .filter(document -> document.getStorageObjectId() != null && !document.getStorageObjectId().isBlank())
                .toList();
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

    @Override
    @Transactional(readOnly = true)
    public ContractDocumentResponse getEffectiveDocument(String contractId) {
        Contract contract = findContractAndCheckAccess(contractId);
        if (contract.getParentContractId() != null) contract = findContractAndCheckAccess(contract.getParentContractId());
        final String rootId = contract.getId();
        // Same source of truth as effective contract fields and billing. SCHEDULED is
        // effective at the boundary even if the synchronization job has not run yet.
        ContractRevision effective = termsResolver.at(contract, billingTime.today());
        String ownerId = effective.getContract().getId();
        if (!rootId.equals(ownerId)) {
            Contract owner = findContractAndCheckAccess(ownerId);
            if (!rootId.equals(owner.getParentContractId()) || !"AMENDMENT".equals(owner.getAgreementKind()))
                throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
        return documentRepository.findByContractIdAndRevisionId(ownerId, effective.getId()).stream()
                .filter(d -> ownerId.equals(d.getContractId()) && effective.getId().equals(d.getRevisionId()))
                .filter(d -> d.getPurpose() == DocumentPurpose.SIGNED_FINAL && d.getDocumentType() == ContractDocumentType.PDF
                        && d.getStatus() == DocumentGenerationStatus.READY && d.getStorageObjectId() != null && !d.getStorageObjectId().isBlank())
                .max(Comparator.comparing(ContractDocument::getGeneratedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ContractDocument::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(this::toDocumentResponse).orElse(null);
    }

    private ContractRevision requireCurrentRevision(Contract contract) {
        if (contract.getCurrentRevisionId() == null) {
            throw new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND);
        }
        return revisionRepository.findById(contract.getCurrentRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND));
    }

    /** Trải các snapshot của revision thành data model phẳng mà poi-tl dùng để render. */
    private Map<String, Object> buildDataModel(Contract contract, ContractRevision revision) {
        Map<String, Object> policies = fromJson(revision.getPoliciesSnapshot(), MAP_TYPE);
        String specialTerms = revision.getSpecialTerms() == null ? "" : revision.getSpecialTerms().trim();
        return renderService.buildDataModelFromSnapshots(
                fromJson(revision.getLandlordSnapshot(), MAP_TYPE),
                fromJson(revision.getTenantSnapshot(), MAP_TYPE),
                fromJson(revision.getPropertySnapshot(), MAP_TYPE),
                fromJson(revision.getLeaseSnapshot(), MAP_TYPE),
                fromJson(revision.getFinancialSnapshot(), MAP_TYPE),
                fromJson(revision.getChargesSnapshot(), CHARGE_LIST_TYPE),
                fromJson(revision.getEquipmentSnapshot(), CHARGE_LIST_TYPE),
                fromJson(revision.getInitialMetersSnapshot(), MAP_TYPE),
                fromJson(revision.getInitialPaymentSnapshot(), MAP_TYPE),
                fromJson(revision.getAmenitiesSnapshot(), CHARGE_LIST_TYPE),
                policies,
                specialTerms,
                revision.getSchemaVersion() != null ? revision.getSchemaVersion() : 2,
                revision.getRevisionNumber(),
                contract.getContractNumber(),
                LocalDate.now(com.hs.common.time.BillingTime.ZONE),
                SIGNING_CITY
        );
    }

    private static boolean isEmptyList(List<?> list) {
        return list == null || list.isEmpty();
    }

    private Contract findContractAndCheckAccess(String contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        checkContractAccess(contract);
        return contract;
    }

    private Contract findContractForUpdateAndCheckAccess(String contractId) {
        if(onboarding!=null) {
            var before=contractRepository.findById(contractId).orElseThrow(()->new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
            checkContractAccess(before);
            if(before.getParentContractId()==null)rentalRequestRepository.findByIdForUpdate(before.getRentalRequestId()).orElseThrow();
        }
        Contract contract = contractRepository.findByIdForUpdate(contractId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        checkContractAccess(contract);
        return contract;
    }

    private void checkContractAccess(Contract contract) {
        String userId = getCurrentUserId();
        boolean landlord = contract.getLandlordId().equals(userId);
        boolean tenant = contract.getTenantId().equals(userId);
        if (!landlord && !tenant && !"system".equals(userId)) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
        if (!landlord && tenant && contract.getStatus() == ContractStatus.DRAFT
                && !"AMENDMENT".equals(contract.getAgreementKind()) && !canTenantReviewRootDraft(contract)) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
    }

    private boolean canTenantReviewRootDraft(Contract contract) {
        return ContractDraftVisibility.tenantMayReview(contract,rentalRequestRepository,paymentRequestRepository);
    }

    private void requireLandlord(Contract contract) {
        String userId = getCurrentUserId();
        if (!contract.getLandlordId().equals(userId) && !"system".equals(userId)) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
    }

    private void requireTenant(Contract contract) {
        if (!contract.getTenantId().equals(getCurrentUserId())) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
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
                .signingCity(SIGNING_CITY)
                .rentalRequestId(c.getRentalRequestId())
                .listingId(c.getListingId())
                .landlordId(c.getLandlordId())
                .tenantId(c.getTenantId())
                .templateId(c.getTemplateId())
                .templateVersionId(c.getTemplateVersionId())
                .currentRevisionId(c.getCurrentRevisionId())
                .parentContractId(c.getParentContractId())
                .agreementKind(c.getAgreementKind())
                .effectiveLeaseMonths(c.getEffectiveLeaseMonths())
                .status(c.getStatus())
                .rentalPaymentId(c.getRentalPaymentId())
                .paymentStatus(paymentStatusOf(c))
                .paidAt(c.getPaidAt())
                .landlordConfirmedAt(c.getLandlordConfirmedAt())
                .signedAt(c.getSignedAt())
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
                .initialPayment(fromJson(r.getInitialPaymentSnapshot(), new TypeReference<Map<String, Object>>() {}))
                .amenities(fromJson(r.getAmenitiesSnapshot(), new TypeReference<List<Map<String, Object>>>() {}))
                .policies(fromJson(r.getPoliciesSnapshot(), new TypeReference<Map<String, Object>>() {}))
                .charges(fromJson(r.getChargesSnapshot(), new TypeReference<List<Map<String, Object>>>() {}))
                .equipments(fromJson(r.getEquipmentSnapshot(), new TypeReference<List<Map<String, Object>>>() {}))
                .meters(fromJson(r.getInitialMetersSnapshot(), new TypeReference<Map<String, Object>>() {}))
                .specialTerms(r.getSpecialTerms())
                .schemaVersion(r.getSchemaVersion() != null ? r.getSchemaVersion() : 2)
                .revisionNote(r.getRevisionNote())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private ContractDocumentResponse toDocumentResponse(ContractDocument d) {
        String viewUrl = null;
        String downloadUrl = null;
        // Superseded previews remain readable history, but never become signable again.
        if (d.getStorageObjectId() != null && (d.getStatus() == DocumentGenerationStatus.READY
                || d.getStatus() == DocumentGenerationStatus.STALE)) {
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
