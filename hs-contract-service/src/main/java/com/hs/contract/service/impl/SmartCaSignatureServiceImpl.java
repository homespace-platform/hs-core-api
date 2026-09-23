package com.hs.contract.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContextHolder;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.client.SmartCaClient;
import com.hs.contract.client.SmartCaClient.*;
import com.hs.contract.config.SmartCaProperties;
import com.hs.contract.dto.signature.*;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractDocument;
import com.hs.contract.model.SignatureRequest;
import com.hs.contract.model.constant.*;
import com.hs.contract.pdf.PdfSignatureEngine;
import com.hs.contract.pdf.PdfSignatureEngine.*;
import com.hs.contract.repository.ContractDocumentRepository;
import com.hs.contract.repository.ContractRepository;
import com.hs.contract.repository.SignatureRequestRepository;
import com.hs.contract.service.SmartCaSignatureService;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.service.StorageService;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ListingStatusService;
import com.hs.listing.service.ParkingReservationService;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.user.model.User;
import com.hs.user.constant.RoleConstants;
import com.hs.user.repository.UserRepository;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation của SmartCA signing flow.
 *
 * <h3>Quan trọng về transaction boundaries</h3>
 * <ul>
 *   <li>Không giữ DB transaction mở khi gọi VNPT API, convert PDF hay upload/download S3.</li>
 *   <li>Mỗi giai đoạn (prepare, poll, embed) là một transaction độc lập.</li>
 *   <li>Idempotency được xử lý bằng cách check status trước khi thực hiện.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartCaSignatureServiceImpl implements SmartCaSignatureService {

    private final SignatureRequestRepository requestRepository;
    private final ContractRepository contractRepository;
    private final ContractDocumentRepository documentRepository;
    private final StorageService storageService;
    private final SmartCaClient smartCaClient;
    private final SmartCaProperties properties;
    private final PdfSignatureEngine pdfEngine;
    private final UserRepository userRepository;
    private final KycVerificationRepository kycVerificationRepository;
    private final PlatformTransactionManager transactionManager;
    private final RentalRequestRepository rentalRequestRepository;
    private final ListingStatusService listingStatusService;
    private final ParkingReservationService parkingReservationService;
    private final PaymentRequestRepository paymentRequestRepository;

    @Value("${homespace.signature.mode:INTERNAL}")
    private String signatureMode;

    @Value("${homespace.smartca.pdf.signature-reason:Ky hop dong thue tren HomeSpace}")
    private String signatureReason;

    @Value("${homespace.smartca.pdf.signature-location:Viet Nam}")
    private String signatureLocation;

    private static final int MAX_EMBEDDING_ATTEMPTS = 3;

    // =========================================================================
    // Public API
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public SignatureStateDto getSignatureState(String contractId, String currentUserId) {
        Contract contract = requireContractAccess(contractId, currentUserId);
        SignerRole myRole = resolveSignerRole(contract, currentUserId);

        List<SignatureRequest> allRequests = requestRepository.findByContractIdOrderByCreatedAtDesc(contractId);

        SignatureRequestDto activeReq = null;
        if (myRole != null) {
            Optional<SignatureRequest> myActive = requestRepository
                    .findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(contractId, myRole);
            if (myActive.isPresent()) {
                activeReq = toDto(myActive.get());
            }
        }

        return SignatureStateDto.builder()
                .signatureMode(signatureMode)
                .smartCaEnabled(properties.isEnabled())
                .activeRequest(activeReq)
                .allRequests(allRequests.stream().map(this::toDto).toList())
                .availableCertificates(Collections.emptyList())
                .build();
    }

    @Override
    public List<CertificateOptionDto> getSigningCertificates(String contractId, String currentUserId) {
        requireSmartCa();
        Contract contract = requireContractAccess(contractId, currentUserId);
        User user = requireUserWithCccd(currentUserId);
        requireSignerRole(contract, currentUserId);

        // Gọi VNPT ngoài transaction (transaction đã kết thúc khi return từ method này)
        List<SmartCaCertificateDto> certs = smartCaClient.getCertificates(user.getCccd(), null);
        return certs.stream().map(c -> CertificateOptionDto.builder()
                .serialNumber(c.serialNumber())
                .subject(c.subject())
                .issuer(c.issuer())
                .validFrom(c.validFrom())
                .validTo(c.validTo())
                .build()).toList();
    }

    @Override
    public SignatureRequestDto initiateSignature(String contractId, String currentUserId,
                                                 InitiateSignatureRequest request) {
        requireSmartCa();
        if (!request.isConsent()) {
            throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED,
                    "Bạn cần đồng ý điều khoản ký số trước khi tiếp tục.");
        }

        // ---- Phase 1: Validate & check idempotency (in transaction) ----
        String existingRequestId = checkIdempotency(contractId, currentUserId);
        if (existingRequestId != null) {
            log.info("Idempotency: returning existing PENDING request {} for contract {}", existingRequestId, contractId);
            return getSignatureRequest(contractId, existingRequestId, currentUserId);
        }

        // ---- Phase 2: Gather data (in transaction) ----
        ContractSigningContext ctx = gatherSigningContext(contractId, currentUserId, request.getCertificateSerial());

        // ---- Phase 3: Prepare PDF (outside transaction — S3 upload) ----
        PrepareResult prepareResult = preparePdfForSigning(ctx);

        // ---- Phase 4: Upload prepared PDF (outside transaction) ----
        String preparedStorageId = uploadPreparedPdf(ctx, prepareResult);

        // ---- Phase 5: Create SignatureRequest in DB (in transaction) ----
        String requestId = createSignatureRequest(ctx, prepareResult, preparedStorageId);

        // ---- Phase 6: Call VNPT (outside transaction) ----
        SmartCaSignResult vnptResult;
        try {
            vnptResult = callVnptSign(ctx, prepareResult);
        } catch (AppException e) {
            // A timeout does not prove VNPT rejected the transaction. Keep CREATED
            // and reconcile by transaction_id; never create a duplicate request.
            if (e.getCode() != ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE.getCode()) {
                markRequestFailed(requestId, "VNPT_REJECTED", e.getMessage());
            }
            throw e;
        }

        // ---- Phase 7: Update request with VNPT result (in transaction) ----
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                updateRequestAfterVnpt(requestId, vnptResult, ctx.contract().getStatus()));

        return getSignatureRequest(contractId, requestId, currentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public SignatureRequestDto getSignatureRequest(String contractId, String requestId, String currentUserId) {
        SignatureRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new AppException(ContractErrorCode.SIGNATURE_REQUEST_NOT_FOUND));
        requireContractAccess(contractId, currentUserId);
        if (!contractId.equals(req.getContractId())) {
            throw new AppException(ContractErrorCode.SIGNATURE_REQUEST_NOT_FOUND);
        }
        return toDto(req);
    }

    @Override
    public SignatureRequestDto refreshSignatureRequest(String contractId, String requestId, String currentUserId) {
        requireSmartCa();

        // Load request (transaction)
        SignatureRequest req = loadRequest(requestId, contractId, currentUserId);

        if (req.getStatus().isTerminal()) {
            return toDto(req);
        }

        // Poll VNPT (outside transaction)
        if (req.getStatus() == SignatureRequestStatus.PENDING_USER_CONFIRMATION
                || req.getStatus() == SignatureRequestStatus.CREATED) {
            pollAndUpdate(req.getId());
        }

        // Reload and return (transaction)
        return toDto(requestRepository.findById(requestId)
                .orElseThrow(() -> new AppException(ContractErrorCode.SIGNATURE_REQUEST_NOT_FOUND)));
    }

    @Override
    public SignatureRequestDto retrySignature(String contractId, String requestId, String currentUserId) {
        requireSmartCa();
        SignatureRequest req = loadRequest(requestId, contractId, currentUserId);

        if (!req.getStatus().canRetry()) {
            throw new AppException(ContractErrorCode.SIGNATURE_RETRY_NOT_ALLOWED);
        }
        if (req.getProviderSignatureValue() != null && !req.getProviderSignatureValue().isBlank()) {
            throw new AppException(ContractErrorCode.SIGNATURE_RETRY_NOT_ALLOWED);
        }
        if ("PROVIDER_STATE_UNKNOWN".equals(req.getFailureCode())) {
            throw new AppException(ContractErrorCode.SIGNATURE_RETRY_NOT_ALLOWED,
                    "Cần đối soát yêu cầu ký cũ với VNPT trước khi gửi lại.");
        }

        // Tạo request mới với attemptNumber tăng
        InitiateSignatureRequest retryReq = new InitiateSignatureRequest();
        retryReq.setCertificateSerial(req.getCertificateSerial());
        retryReq.setConsent(true);

        // Cancel request cũ
        cancelRequest(req.getId());

        return initiateSignature(contractId, currentUserId, retryReq);
    }

    @Override
    public void processPendingRequest(String requestId) {
        pollAndUpdate(requestId);
    }

    @Override
    public void embedProviderSignature(String requestId) {
        // ---- Step 1: Atomic claim PROVIDER_SIGNED → EMBEDDING ----
        int claimed = requestRepository.claimForEmbedding(requestId, Instant.now());
        if (claimed == 0) {
            log.debug("embedProviderSignature: request {} already claimed by another worker", requestId);
            return;
        }

        // ---- Step 2: Load data needed for embedding (transaction) ----
        EmbeddingContext embCtx = loadEmbeddingContext(requestId);
        if (embCtx == null) return;

        // ---- Step 3: Download prepared PDF from storage (outside transaction) ----
        byte[] preparedPdf;
        try {
            preparedPdf = storageService.downloadDirect(embCtx.preparedStorageId());
        } catch (Exception e) {
            log.error("embedProviderSignature: failed to download prepared PDF for request {}: {}", requestId, e.getMessage());
            markEmbeddingFailed(requestId, "DOWNLOAD_FAILED", "Không thể tải prepared PDF từ storage");
            return;
        }

        // ---- Step 4: Embed signature (outside transaction) ----
        byte[] signedPdf;
        try {
            signedPdf = pdfEngine.embedSignatureFromStorage(
                    preparedPdf,
                    embCtx.placeholderOffset(),
                    embCtx.placeholderLength(),
                    embCtx.providerSignatureValue(),
                    embCtx.certificateData(),
                    embCtx.byteRange()
            );
        } catch (Exception e) {
            log.error("embedProviderSignature: failed to embed signature for request {}: {}", requestId, e.getMessage());
            markEmbeddingFailed(requestId, "EMBED_FAILED", "Không thể nhúng chữ ký vào PDF: " + e.getMessage());
            return;
        }

        // ---- Step 5: Verify (outside transaction) ----
        try {
            List<VerificationResult> verifications = pdfEngine.verifySignatures(signedPdf);
            int expected = embCtx.signerRole() == SignerRole.LANDLORD ? 1 : 2;
            boolean allValid = verifications.size() == expected
                    && verifications.stream().allMatch(VerificationResult::valid);
            if (allValid) {
                allValid = verifications.stream().anyMatch(v -> v.signerSubject().contains(embCtx.signerCccd()));
            }
            if (allValid && embCtx.signerRole() == SignerRole.TENANT) {
                String landlordCccd = requestRepository.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(
                        embCtx.contractId(), SignerRole.LANDLORD)
                        .filter(r -> r.getStatus() == SignatureRequestStatus.SIGNED)
                        .map(SignatureRequest::getSignerCccd).orElse("");
                allValid = !landlordCccd.isBlank()
                        && verifications.stream().anyMatch(v -> v.signerSubject().contains(landlordCccd));
            }
            if (!allValid) {
                log.error("embedProviderSignature: expected {} valid signatures, got {} for request {}",
                        expected, verifications.size(), requestId);
                markEmbeddingFailed(requestId, "SIGNATURE_VERIFY_FAILED", "PDF không vượt qua xác minh chữ ký số");
                return;
            } else {
                log.info("embedProviderSignature: all signatures verified OK for request {}", requestId);
            }
        } catch (Exception e) {
            log.error("embedProviderSignature: verification error for request {}: {}", requestId, e.getMessage());
            markEmbeddingFailed(requestId, "SIGNATURE_VERIFY_FAILED", "Không thể xác minh chữ ký trong PDF");
            return;
        }

        // ---- Step 6: Upload signed PDF (outside transaction) ----
        String signedStorageId;
        try {
            signedStorageId = uploadSignedPdf(embCtx, signedPdf);
        } catch (Exception e) {
            log.error("embedProviderSignature: failed to upload signed PDF for request {}: {}", requestId, e.getMessage());
            markEmbeddingFailed(requestId, "UPLOAD_FAILED", "Không thể lưu PDF đã ký lên storage");
            return;
        }

        // ---- Step 7: Finalize (transaction) ----
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    finalizeSignedRequest(requestId, signedStorageId, signedPdf.length, embCtx));
        } catch (Exception e) {
            log.error("Finalizing SmartCA signature failed for request {}: {}", requestId, e.getMessage(), e);
            markEmbeddingFailed(requestId, "FINALIZE_FAILED", "Không thể hoàn tất hợp đồng sau khi ký");
        }
    }

    @Override
    public void handleWebhook(String tranCode) {
        if (!properties.isWebhookEnabled()) {
            log.warn("Webhook received but webhookEnabled=false. tranCode={}", tranCode);
            return;
        }
        Optional<SignatureRequest> reqOpt = requestRepository.findByProviderTranCode(tranCode);
        if (reqOpt.isEmpty()) {
            log.warn("handleWebhook: no request found for tranCode={}", tranCode);
            return;
        }
        processPendingRequest(reqOpt.get().getId());
    }

    // =========================================================================
    // Private — signing phases
    // =========================================================================

    /** Kiểm tra idempotency: nếu đã có request PENDING trả về requestId, ngược lại null. */
    private String checkIdempotency(String contractId, String currentUserId) {
        Contract contract = requireContractAccess(contractId, currentUserId);
        SignerRole role = requireSignerRole(contract, currentUserId);

        List<SignatureRequestStatus> activeStatuses = List.of(
                SignatureRequestStatus.CREATED,
                SignatureRequestStatus.PENDING_USER_CONFIRMATION,
                SignatureRequestStatus.PROVIDER_SIGNED,
                SignatureRequestStatus.EMBEDDING
        );
        Optional<SignatureRequest> existing = requestRepository
                .findFirstByContractIdAndSignerRoleAndStatusIn(contractId, role, activeStatuses);
        return existing.map(SignatureRequest::getId).orElse(null);
    }

    /** Thu thập tất cả thông tin cần cho signing (trong transaction). */
    private ContractSigningContext gatherSigningContext(String contractId, String currentUserId, String preferredSerial) {
        Contract contract = requireContractAccess(contractId, currentUserId);
        SignerRole role = requireSignerRole(contract, currentUserId);
        User user = requireUserWithCccd(currentUserId);

        // Xác thực trạng thái hợp đồng cho phép ký
        validateContractStatusForSigning(contract, role);

        // Lấy PDF nguồn cần ký
        ContractDocument sourcePdf = findSigningSourcePdf(contract, role);

        // Lấy cert
        List<SmartCaCertificateDto> certs = smartCaClient.getCertificates(user.getCccd(), preferredSerial);
        if (certs.isEmpty()) {
            throw new AppException(ContractErrorCode.SIGNATURE_CERTIFICATE_NOT_FOUND);
        }
        SmartCaCertificateDto cert = preferredSerial != null
                ? certs.stream().filter(c -> preferredSerial.equalsIgnoreCase(c.serialNumber())).findFirst()
                    .orElseThrow(() -> new AppException(ContractErrorCode.SIGNATURE_CERTIFICATE_NOT_FOUND))
                : certs.get(0);

        String docId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String transactionId = UUID.randomUUID().toString();

        return new ContractSigningContext(contract, user, role, cert, sourcePdf, docId, transactionId);
    }

    private PrepareResult preparePdfForSigning(ContractSigningContext ctx) {
        byte[] pdfBytes;
        try {
            pdfBytes = storageService.downloadDirect(ctx.sourcePdf().getStorageObjectId());
        } catch (Exception e) {
            log.error("Cannot download PDF for signing: {}", e.getMessage());
            throw new AppException(ContractErrorCode.SIGNATURE_PDF_NOT_READY);
        }

        SigningParams signingParams = new SigningParams(
                signatureReason,
                signatureLocation,
                null,
                ctx.cert().subject(),
                ctx.role().name()
        );

        try {
            return pdfEngine.prepareForSigning(pdfBytes, signingParams);
        } catch (Exception e) {
            log.error("PDF prepare failed for contract {}: {}", ctx.contract().getId(), e.getMessage(), e);
            throw new AppException(ContractErrorCode.SIGNATURE_PDF_EMBED_FAILED,
                    "Không thể chuẩn bị PDF cho ký số: " + e.getMessage());
        }
    }

    private String uploadPreparedPdf(ContractSigningContext ctx, PrepareResult prep) {
        String fileName = ctx.contract().getContractNumber() + "_prepared_" + ctx.role().name().toLowerCase() + ".pdf";
        var stored = storageService.uploadDirect(
                prep.preparedPdfBytes(),
                fileName,
                "application/pdf",
                StoragePurpose.SIGNATURE_PREPARED_DOCUMENT,
                "CONTRACT",
                ctx.contract().getId(),
                StorageVisibility.PRIVATE
        );
        log.info("Uploaded prepared PDF: storageId={} size={}", stored.id(), prep.preparedPdfBytes().length);
        return stored.id();
    }

    private String createSignatureRequest(ContractSigningContext ctx, PrepareResult prep, String preparedStorageId) {
        SignatureRequest req = SignatureRequest.builder()
                .contractId(ctx.contract().getId())
                .revisionId(ctx.contract().getCurrentRevisionId())
                .sourceDocumentId(ctx.sourcePdf().getId())
                .signerUserId(ctx.user().getId())
                .signerRole(ctx.role())
                .signerCccd(ctx.user().getCccd())
                .status(SignatureRequestStatus.CREATED)
                .docId(ctx.docId())
                .providerTransactionId(ctx.transactionId())
                .certificateSerial(ctx.cert().serialNumber())
                .certificateSubject(ctx.cert().subject())
                .providerCertData(ctx.cert().certData())
                .providerChainData(ctx.cert().chainData())
                .preparedDocumentStorageId(preparedStorageId)
                .preparedByteRange(prep.byteRangeAsString())
                .preparedPlaceholderOffset(prep.placeholderOffset())
                .preparedPlaceholderLength(prep.placeholderLength())
                .preparedSignDate(prep.signDate())
                .initiatedAt(Instant.now())
                .expiresAt(Instant.now().plus(properties.getTransactionTimeout()))
                .attemptNumber(1)
                .build();
        requestRepository.save(req);
        log.info("Created SignatureRequest id={} for contract={} role={}", req.getId(), ctx.contract().getId(), ctx.role());
        return req.getId();
    }

    private SmartCaSignResult callVnptSign(ContractSigningContext ctx, PrepareResult prep) {
        SmartCaSignParams params = new SmartCaSignParams(
                ctx.user().getCccd(),
                ctx.cert().serialNumber(),
                ctx.transactionId(),
                ctx.docId(),
                ctx.contract().getContractNumber(),
                prep.hashHex()
        );
        return smartCaClient.createSignatureRequest(params);
    }

    private void updateRequestAfterVnpt(String requestId, SmartCaSignResult vnptResult, ContractStatus currentContractStatus) {
        SignatureRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new AppException(ContractErrorCode.SIGNATURE_REQUEST_NOT_FOUND));
        req.setProviderTransactionId(vnptResult.providerTransactionId());
        req.setProviderTranCode(vnptResult.providerTranCode());
        req.setStatus(SignatureRequestStatus.PENDING_USER_CONFIRMATION);
        requestRepository.save(req);

        // Cập nhật trạng thái hợp đồng
        Contract contract = contractRepository.findById(req.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (req.getSignerRole() == SignerRole.LANDLORD && contract.getStatus() == ContractStatus.DRAFT) {
            contract.setStatus(ContractStatus.LANDLORD_SIGNATURE_PENDING);
            contractRepository.save(contract);
        } else if (req.getSignerRole() == SignerRole.TENANT && contract.getStatus() == ContractStatus.TENANT_SIGNATURE_PENDING) {
            // Giữ nguyên — tenant ký vẫn TENANT_SIGNATURE_PENDING cho đến khi hoàn tất
        }
        log.info("Request {} updated to PENDING_USER_CONFIRMATION, tranCode={}", requestId, vnptResult.providerTranCode());
    }

    // =========================================================================
    // Private — polling
    // =========================================================================

    /**
     * Poll VNPT và cập nhật trạng thái request.
     * Nếu SIGNED → chuyển sang PROVIDER_SIGNED (embedding worker sẽ pick up).
     */
    private void pollAndUpdate(String requestId) {
        // Load (brief transaction)
        SignatureRequest req = requestRepository.findById(requestId).orElse(null);
        if (req == null || (req.getStatus() != SignatureRequestStatus.PENDING_USER_CONFIRMATION
                && req.getStatus() != SignatureRequestStatus.CREATED)) return;

        String transactionId = req.getProviderTransactionId();
        if (transactionId == null || transactionId.isBlank()) {
            log.warn("pollAndUpdate: no providerTransactionId for request {}", requestId);
            return;
        }

        // Poll VNPT (outside transaction)
        SmartCaStatusResult result;
        try {
            result = smartCaClient.getSignatureStatus(transactionId, req.getDocId());
        } catch (AppException e) {
            log.warn("pollAndUpdate: VNPT error for request {}: {}", requestId, e.getMessage());
            if (req.getStatus() == SignatureRequestStatus.CREATED
                    && req.getExpiresAt() != null && Instant.now().isAfter(req.getExpiresAt())) {
                markRequestFailed(requestId, "PROVIDER_STATE_UNKNOWN",
                        "Không xác định được trạng thái yêu cầu ký tại VNPT; cần đối soát trước khi gửi lại");
            }
            updateLastChecked(requestId);
            return;
        } catch (Exception e) {
            log.error("pollAndUpdate: unexpected error for request {}: {}", requestId, e.getMessage());
            return;
        }

        // Update (transaction)
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> updateAfterPoll(requestId, result));
    }

    private void updateAfterPoll(String requestId, SmartCaStatusResult result) {
        SignatureRequest req = requestRepository.findById(requestId).orElse(null);
        if (req == null || (req.getStatus() != SignatureRequestStatus.PENDING_USER_CONFIRMATION
                && req.getStatus() != SignatureRequestStatus.CREATED)) return;

        req.setLastProviderCheckedAt(Instant.now());

        if (result.isSigned()) {
            log.info("Request {} SIGNED by VNPT — saving signature data, transitioning to PROVIDER_SIGNED", requestId);
            if (req.getStatus() == SignatureRequestStatus.CREATED && req.getSignerRole() == SignerRole.LANDLORD) {
                Contract contract = contractRepository.findById(req.getContractId()).orElse(null);
                if (contract != null && contract.getStatus() == ContractStatus.DRAFT) {
                    contract.setStatus(ContractStatus.LANDLORD_SIGNATURE_PENDING);
                    contractRepository.save(contract);
                }
            }
            req.setProviderSignatureValue(result.signatureValue()); // NOT logged
            // Bind the response to the certificate selected for this CCCD at initiation.
            // A different certificate from the status response must not silently change identity.
            req.setConfirmedAt(result.signedTime() != null ? result.signedTime() : Instant.now());
            req.setStatus(SignatureRequestStatus.PROVIDER_SIGNED);
        } else if (result.isRejectedOrFailed()) {
            log.info("Request {} rejected/failed by VNPT: status={}", requestId, result.status());
            req.setStatus(SignatureRequestStatus.REJECTED);
            req.setFailedAt(Instant.now());
            req.setFailureCode(result.failureCode());
            req.setFailureMessage(result.failureMessage());

            // Revert contract status
            revertContractStatus(req);
        } else {
            if (req.getStatus() == SignatureRequestStatus.CREATED) {
                req.setStatus(SignatureRequestStatus.PENDING_USER_CONFIRMATION);
                Contract contract = contractRepository.findById(req.getContractId()).orElse(null);
                if (contract != null && req.getSignerRole() == SignerRole.LANDLORD
                        && contract.getStatus() == ContractStatus.DRAFT) {
                    contract.setStatus(ContractStatus.LANDLORD_SIGNATURE_PENDING);
                    contractRepository.save(contract);
                }
            }
            // Kiểm tra timeout
            if (req.getExpiresAt() != null && Instant.now().isAfter(req.getExpiresAt())) {
                log.info("Request {} expired (no action from user)", requestId);
                req.setStatus(SignatureRequestStatus.EXPIRED);
                req.setFailedAt(Instant.now());
                req.setFailureMessage("Yêu cầu ký số đã hết hạn — người dùng không xác nhận trong thời gian quy định");
                revertContractStatus(req);
            }
        }
        requestRepository.save(req);
    }

    private void updateLastChecked(String requestId) {
        requestRepository.findById(requestId).ifPresent(req -> {
            req.setLastProviderCheckedAt(Instant.now());
            requestRepository.save(req);
        });
    }

    // =========================================================================
    // Private — embedding
    // =========================================================================

    private record EmbeddingContext(
            String requestId,
            String contractId,
            SignerRole signerRole,
            String preparedStorageId,
            long placeholderOffset,
            int placeholderLength,
            String providerSignatureValue,
            String certificateData,
            String byteRange,
            String signerCccd,
            String contractNumber,
            int attemptNumber,
            String sourcePdfRevisionId
    ) {}

    private EmbeddingContext loadEmbeddingContext(String requestId) {
        SignatureRequest req = requestRepository.findById(requestId).orElse(null);
        if (req == null || req.getStatus() != SignatureRequestStatus.EMBEDDING) {
            log.warn("loadEmbeddingContext: request {} not in EMBEDDING status", requestId);
            return null;
        }
        if (req.getProviderSignatureValue() == null || req.getProviderSignatureValue().isBlank()) {
            log.error("loadEmbeddingContext: no providerSignatureValue for request {}", requestId);
            markEmbeddingFailed(requestId, "NO_SIGNATURE_VALUE", "Không có dữ liệu chữ ký từ VNPT");
            return null;
        }
        Contract contract = contractRepository.findById(req.getContractId()).orElse(null);
        return new EmbeddingContext(
                requestId,
                req.getContractId(),
                req.getSignerRole(),
                req.getPreparedDocumentStorageId(),
                req.getPreparedPlaceholderOffset(),
                req.getPreparedPlaceholderLength(),
                req.getProviderSignatureValue(),
                req.getProviderCertData(),
                req.getPreparedByteRange(),
                req.getSignerCccd(),
                contract != null ? contract.getContractNumber() : req.getContractId(),
                req.getAttemptNumber(),
                req.getRevisionId()
        );
    }

    private String uploadSignedPdf(EmbeddingContext ctx, byte[] signedPdf) {
        DocumentPurpose purpose = ctx.signerRole() == SignerRole.LANDLORD
                ? DocumentPurpose.SIGNED_LANDLORD
                : DocumentPurpose.SIGNED_FINAL;

        String suffix = ctx.signerRole() == SignerRole.LANDLORD ? "signed_landlord" : "signed_final";
        String fileName = ctx.contractNumber() + "_" + suffix + ".pdf";

        var stored = storageService.uploadDirect(
                signedPdf,
                fileName,
                "application/pdf",
                StoragePurpose.CONTRACT_DOCUMENT,
                "CONTRACT",
                ctx.contractId(),
                StorageVisibility.PRIVATE
        );
        log.info("Uploaded signed PDF: storageId={} size={}", stored.id(), signedPdf.length);
        return stored.id();
    }

    private void finalizeSignedRequest(String requestId, String signedStorageId, long signedPdfSize, EmbeddingContext ctx) {
        SignatureRequest req = requestRepository.findById(requestId).orElse(null);
        if (req == null || req.getStatus() != SignatureRequestStatus.EMBEDDING) return;
        Contract contract = contractRepository.findById(ctx.contractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!ctx.sourcePdfRevisionId().equals(contract.getCurrentRevisionId())) {
            throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED, "Hợp đồng đã đổi phiên bản trong khi ký");
        }
        ContractDocument source = documentRepository.findById(req.getSourceDocumentId())
                .orElseThrow(() -> new AppException(ContractErrorCode.SIGNATURE_PDF_NOT_READY));

        // Lưu document record
        ContractDocument signedDoc = ContractDocument.builder()
                .contractId(ctx.contractId())
                .revisionId(ctx.sourcePdfRevisionId())
                .templateVersionId(source.getTemplateVersionId())
                .documentType(ContractDocumentType.PDF)
                .purpose(ctx.signerRole() == SignerRole.LANDLORD ? DocumentPurpose.SIGNED_LANDLORD : DocumentPurpose.SIGNED_FINAL)
                .storageObjectId(signedStorageId)
                .fileName(ctx.contractNumber() + (ctx.signerRole() == SignerRole.LANDLORD ? "_signed_landlord" : "_signed_final") + ".pdf")
                .fileSize(signedPdfSize)
                .status(DocumentGenerationStatus.READY)
                .generatedAt(Instant.now())
                .build();
        signedDoc = documentRepository.save(signedDoc);

        // Cập nhật request
        req.setStatus(SignatureRequestStatus.SIGNED);
        req.setSignedDocumentId(signedDoc.getId());
        requestRepository.save(req);

        // Cập nhật contract status
            if (ctx.signerRole() == SignerRole.LANDLORD) {
                if (contract.getStatus() != ContractStatus.LANDLORD_SIGNATURE_PENDING) {
                    throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED);
                }
                contract.setStatus(ContractStatus.TENANT_SIGNATURE_PENDING);
                contract.setLandlordConfirmedAt(Instant.now());
                log.info("Contract {} landlord signed — now TENANT_SIGNATURE_PENDING", ctx.contractId());
            } else {
                if (contract.getStatus() != ContractStatus.TENANT_SIGNATURE_PENDING
                        || requestRepository.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(
                        contract.getId(), SignerRole.LANDLORD)
                        .filter(r -> r.getStatus() == SignatureRequestStatus.SIGNED).isEmpty()) {
                    throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED);
                }
                activateAfterBothSignatures(contract);
                contract.setStatus(ContractStatus.ACTIVE);
                contract.setSignedAt(Instant.now());
                log.info("Contract {} tenant signed — now ACTIVE", ctx.contractId());
            }
            contractRepository.save(contract);

        log.info("Request {} SIGNED: signedDocId={}", requestId, signedDoc.getId());
    }

    private void activateAfterBothSignatures(Contract contract) {
        if (contract.getRentalPaymentId() != null) {
            PaymentRequest payment = paymentRequestRepository.findById(contract.getRentalPaymentId())
                    .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED));
            if (payment.getStatus() != PaymentStatus.CONFIRMED) {
                throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED);
            }
        } else if (contract.getPaymentStatus() != com.hs.contract.model.constant.ContractPaymentStatus.PAID
                && contract.getPaymentStatus() != com.hs.contract.model.constant.ContractPaymentStatus.PAID_MOCK) {
            throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED);
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
        parkingReservationService.activateReservationsForRequest(rentalRequest.getId(), contract.getId());
    }

    private void markEmbeddingFailed(String requestId, String code, String message) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            requestRepository.findById(requestId).ifPresent(req -> {
            if (req.getProcessingAttempts() >= MAX_EMBEDDING_ATTEMPTS) {
                req.setStatus(SignatureRequestStatus.FAILED);
                req.setFailedAt(Instant.now());
                req.setFailureCode(code);
                req.setFailureMessage(message);
                // VNPT has already signed. Keep contract pending for manual reconciliation;
                // never return to DRAFT and create a second provider transaction.
            } else {
                // Trả lại PROVIDER_SIGNED để thử lại sau
                req.setStatus(SignatureRequestStatus.PROVIDER_SIGNED);
            }
            requestRepository.save(req);
            }));
    }

    private void revertContractStatus(SignatureRequest req) {
        Contract contract = contractRepository.findById(req.getContractId()).orElse(null);
        if (contract == null) return;
        if (req.getSignerRole() == SignerRole.LANDLORD
                && contract.getStatus() == ContractStatus.LANDLORD_SIGNATURE_PENDING) {
            contract.setStatus(ContractStatus.DRAFT);
            contractRepository.save(contract);
        }
        // Nếu tenant bị fail, hợp đồng vẫn ở TENANT_SIGNATURE_PENDING (tenant có thể retry)
    }

    private void cancelRequest(String requestId) {
        requestRepository.findById(requestId).ifPresent(req -> {
            req.setStatus(SignatureRequestStatus.CANCELLED);
            req.setFailedAt(Instant.now());
            requestRepository.save(req);
        });
    }

    private void markRequestFailed(String requestId, String code, String message) {
        requestRepository.findById(requestId).ifPresent(req -> {
            req.setStatus(SignatureRequestStatus.FAILED);
            req.setFailedAt(Instant.now());
            req.setFailureCode(code);
            req.setFailureMessage(message);
            requestRepository.save(req);
        });
    }

    // =========================================================================
    // Private — validation helpers
    // =========================================================================

    private void requireSmartCa() {
        if (!"SMARTCA".equalsIgnoreCase(signatureMode) || !properties.isEnabled()) {
            throw new AppException(ContractErrorCode.SIGNATURE_MODE_NOT_ENABLED);
        }
    }

    private Contract requireContractAccess(String contractId, String userId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!userId.equals(contract.getLandlordId()) && !userId.equals(contract.getTenantId())) {
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        }
        return contract;
    }

    private SignerRole resolveSignerRole(Contract contract, String userId) {
        if (userId.equals(contract.getLandlordId())) return SignerRole.LANDLORD;
        if (userId.equals(contract.getTenantId())) return SignerRole.TENANT;
        return null;
    }

    private SignerRole requireSignerRole(Contract contract, String userId) {
        SignerRole role = resolveSignerRole(contract, userId);
        if (role == null) throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED);
        return role;
    }

    User requireUserWithCccd(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED));
        if (user.getCccd() == null || user.getCccd().isBlank()) {
            throw new AppException(ContractErrorCode.SIGNATURE_IDENTITY_MISSING);
        }
        // Bootstrap/admin accounts are exempt from Didit KYC, but still need a CCCD
        // matching an active SmartCA certificate before a signature can be initiated.
        if ((user.getRole() == null || !RoleConstants.ADMIN.equals(user.getRole().getName()))
                && !kycVerificationRepository.existsByUserIdAndProviderAndStatus(
                userId, KycProvider.DIDIT, KycStatus.VERIFIED)) {
            throw new AppException(ContractErrorCode.SIGNATURE_IDENTITY_MISSING);
        }
        return user;
    }

    private void validateContractStatusForSigning(Contract contract, SignerRole role) {
        if (contract.getRentalPaymentId() != null) {
            PaymentRequest payment = paymentRequestRepository.findById(contract.getRentalPaymentId())
                    .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED));
            if (payment.getStatus() != PaymentStatus.CONFIRMED) {
                throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED);
            }
        } else if (contract.getPaymentStatus() != com.hs.contract.model.constant.ContractPaymentStatus.PAID
                && contract.getPaymentStatus() != com.hs.contract.model.constant.ContractPaymentStatus.PAID_MOCK) {
            throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED);
        }
        ContractStatus status = contract.getStatus();
        if (role == SignerRole.LANDLORD) {
            if (status != ContractStatus.DRAFT && status != ContractStatus.LANDLORD_SIGNATURE_PENDING) {
                throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED,
                        "Hợp đồng không ở trạng thái chờ chủ nhà ký.");
            }
        } else {
            if (status != ContractStatus.TENANT_SIGNATURE_PENDING) {
                throw new AppException(ContractErrorCode.SIGNATURE_NOT_ALLOWED,
                        "Chủ nhà chưa ký hoặc hợp đồng không ở trạng thái chờ người thuê ký.");
            }
        }
    }

    private ContractDocument findSigningSourcePdf(Contract contract, SignerRole role) {
        // Chủ nhà ký vào PDF OFFICIAL/PREVIEW của revision hiện tại
        // Người thuê ký vào PDF đã có chữ ký chủ nhà (SIGNED_LANDLORD)
        String revisionId = contract.getCurrentRevisionId();

        if (role == SignerRole.TENANT) {
            // Tìm SIGNED_LANDLORD PDF trước
            List<ContractDocument> docs = documentRepository.findByContractIdAndRevisionId(
                    contract.getId(), revisionId);
            Optional<ContractDocument> signedByLandlord = docs.stream()
                    .filter(d -> d.getPurpose() == DocumentPurpose.SIGNED_LANDLORD
                            && d.getDocumentType() == ContractDocumentType.PDF
                            && d.getStatus() == DocumentGenerationStatus.READY)
                    .findFirst();
            return signedByLandlord.orElseThrow(() ->
                    new AppException(ContractErrorCode.SIGNATURE_PDF_NOT_READY,
                            "Chưa tìm thấy PDF có chữ ký chủ nhà. Vui lòng đợi hệ thống xử lý."));
        }

        // Landlord ký vào PDF PREVIEW hoặc OFFICIAL
        Optional<ContractDocument> pdf = documentRepository
                .findFirstByContractIdAndRevisionIdAndDocumentTypeOrderByGeneratedAtDesc(
                        contract.getId(), revisionId, ContractDocumentType.PDF);
        return pdf.filter(d -> d.getStatus() == DocumentGenerationStatus.READY
                && (d.getPurpose() == DocumentPurpose.PREVIEW || d.getPurpose() == DocumentPurpose.OFFICIAL))
                .orElseThrow(() ->
                new AppException(ContractErrorCode.SIGNATURE_PDF_NOT_READY,
                        "Hợp đồng chưa có PDF — vui lòng kết xuất tài liệu trước khi ký số."));
    }

    private SignatureRequest loadRequest(String requestId, String contractId, String currentUserId) {
        requireContractAccess(contractId, currentUserId);
        return requestRepository.findById(requestId)
                .filter(req -> contractId.equals(req.getContractId()))
                .orElseThrow(() -> new AppException(ContractErrorCode.SIGNATURE_REQUEST_NOT_FOUND));
    }

    // =========================================================================
    // Private — mapper
    // =========================================================================

    private SignatureRequestDto toDto(SignatureRequest req) {
        return SignatureRequestDto.builder()
                .id(req.getId())
                .contractId(req.getContractId())
                .revisionId(req.getRevisionId())
                .signerRole(req.getSignerRole())
                .status(req.getStatus())
                .docId(req.getDocId())
                .signedDocumentId(req.getSignedDocumentId())
                .certificateSerial(req.getCertificateSerial())
                .certificateSubject(req.getCertificateSubject())
                .initiatedAt(req.getInitiatedAt())
                .confirmedAt(req.getConfirmedAt())
                .expiresAt(req.getExpiresAt())
                .failedAt(req.getFailedAt())
                .failureCode(req.getFailureCode())
                .failureMessage(req.getFailureMessage())
                .attemptNumber(req.getAttemptNumber())
                .processingAttempts(req.getProcessingAttempts())
                .failureReason(buildFailureReason(req))
                .build();
    }

    private String buildFailureReason(SignatureRequest req) {
        if (req.getStatus() == SignatureRequestStatus.REJECTED) {
            return "Bạn đã từ chối yêu cầu ký số trên ứng dụng VNPT SmartCA.";
        }
        if (req.getStatus() == SignatureRequestStatus.EXPIRED) {
            return "Yêu cầu ký số đã hết hạn — vui lòng thử lại.";
        }
        if (req.getStatus() == SignatureRequestStatus.FAILED) {
            return req.getFailureMessage() != null ? req.getFailureMessage()
                    : "Quá trình ký số gặp lỗi — vui lòng liên hệ hỗ trợ.";
        }
        return null;
    }

    // =========================================================================
    // Private — context record
    // =========================================================================

    private record ContractSigningContext(
            Contract contract,
            User user,
            SignerRole role,
            SmartCaCertificateDto cert,
            ContractDocument sourcePdf,
            String docId,
            String transactionId
    ) {}
}
