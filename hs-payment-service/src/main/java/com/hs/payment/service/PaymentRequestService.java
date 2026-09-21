package com.hs.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.qr.VietQrProvider;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.repository.StorageObjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRequestService {

    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentLineItemRepository paymentLineItemRepository;
    private final PaymentEvidenceRepository paymentEvidenceRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final DepositRecordRepository depositRecordRepository;
    private final BankAccountService bankAccountService;
    private final VietQrProvider vietQrProvider;
    private final TransferReferenceGenerator transferReferenceGenerator;
    private final ObjectMapper objectMapper;
    private final StorageObjectRepository storageObjectRepository;
    private final PaymentProofUploadSessionService proofUploadSessionService;

    @Value("${homespace.payment.confirmation-window-hours:24}")
    private long confirmationWindowHours = 24;

    @Value("${homespace.payment.contract-due-hours:48}")
    private long contractDueHours = 48;

    @Transactional
    public PaymentRequest createInitialPayment(
            String rentalRequestId,
            String listingId,
            String payerId,
            String payeeId,
            BigDecimal monthlyRent,
            BigDecimal monthlyCharges,
            BigDecimal depositAmount,
            BigDecimal totalAmount,
            String breakdownSnapshot,
            String excludedChargesSnapshot,
            Instant holdExpiresAt
    ) {
        // Idempotency: return existing if present
        Optional<PaymentRequest> existing = paymentRequestRepository.findByRentalRequestIdAndType(
                rentalRequestId, PaymentType.INITIAL);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Validate bank accounts of both parties
        BankAccount payeeAccount = bankAccountService.getDefaultIncomingAccount(payeeId);
        BankAccount payerAccount = bankAccountService.getDefaultRefundAccount(payerId);

        String ref = transferReferenceGenerator.generateUniqueReference();

        // Generate VietQR for tenant to transfer directly to landlord's incoming bank account
        String qrUrl = vietQrProvider.generateQrImageUrl(
                payeeAccount.getBankBin(),
                payeeAccount.getAccountNumber(),
                payeeAccount.getAccountHolderName(),
                totalAmount,
                ref
        );

        String payeeSnapshotJson = toJson(new BankAccountSnapshotDto(
                payeeAccount.getBankBin(),
                payeeAccount.getBankCode(),
                payeeAccount.getBankName(),
                payeeAccount.getAccountNumber(),
                payeeAccount.getAccountHolderName()
        ));

        String payerSnapshotJson = toJson(new BankAccountSnapshotDto(
                payerAccount.getBankBin(),
                payerAccount.getBankCode(),
                payerAccount.getBankName(),
                payerAccount.getAccountNumber(),
                payerAccount.getAccountHolderName()
        ));

        PaymentRequest payment = PaymentRequest.builder()
                .rentalRequestId(rentalRequestId)
                .listingId(listingId)
                .payerId(payerId)
                .payeeId(payeeId)
                .type(PaymentType.INITIAL)
                .direction(PaymentDirection.TENANT_TO_LANDLORD)
                .status(PaymentStatus.AWAITING_TRANSFER)
                .currency("VND")
                .totalAmount(totalAmount)
                .transferReference(ref)
                .payerBankAccountSnapshot(payerSnapshotJson)
                .payeeBankAccountSnapshot(payeeSnapshotJson)
                .breakdownSnapshot(breakdownSnapshot)
                .excludedChargesSnapshot(excludedChargesSnapshot)
                .qrProvider("VIETQR_QUICK_LINK")
                .qrImageUrl(qrUrl)
                .dueAt(holdExpiresAt)
                .build();

        PaymentRequest saved = paymentRequestRepository.save(payment);

        // Save line items
        int sort = 0;
        if (monthlyRent != null && monthlyRent.compareTo(BigDecimal.ZERO) > 0) {
            paymentLineItemRepository.save(PaymentLineItem.builder()
                    .paymentRequestId(saved.getId())
                    .type("RENT")
                    .displayName("Tiền thuê kỳ đầu")
                    .amount(monthlyRent)
                    .quantity(1)
                    .unitPrice(monthlyRent)
                    .sortOrder(sort++)
                    .build());
        }
        if (monthlyCharges != null && monthlyCharges.compareTo(BigDecimal.ZERO) > 0) {
            paymentLineItemRepository.save(PaymentLineItem.builder()
                    .paymentRequestId(saved.getId())
                    .type("SERVICE_FEE")
                    .displayName("Chi phí cố định kỳ đầu")
                    .amount(monthlyCharges)
                    .quantity(1)
                    .unitPrice(monthlyCharges)
                    .sortOrder(sort++)
                    .build());
        }
        if (depositAmount != null && depositAmount.compareTo(BigDecimal.ZERO) > 0) {
            paymentLineItemRepository.save(PaymentLineItem.builder()
                    .paymentRequestId(saved.getId())
                    .type("DEPOSIT")
                    .displayName("Tiền đặt cọc")
                    .amount(depositAmount)
                    .quantity(1)
                    .unitPrice(depositAmount)
                    .sortOrder(sort++)
                    .build());
        }

        // Initialize DepositRecord in PENDING status
        if (depositAmount != null && depositAmount.compareTo(BigDecimal.ZERO) > 0) {
            DepositRecord depositRecord = DepositRecord.builder()
                    .rentalRequestId(rentalRequestId)
                    .initialPaymentRequestId(saved.getId())
                    .landlordId(payeeId)
                    .tenantId(payerId)
                    .originalAmount(depositAmount)
                    .heldAmount(BigDecimal.ZERO)
                    .refundableAmount(depositAmount)
                    .status(DepositStatus.PENDING)
                    .build();
            depositRecordRepository.save(depositRecord);
        }

        recordEvent(saved.getId(), PaymentEventType.CREATED, null, PaymentStatus.AWAITING_TRANSFER,
                payeeId, "LANDLORD", "Tạo yêu cầu chuyển khoản ban đầu giữ chỗ", null);

        log.info("Created INITIAL PaymentRequest [{}] ref=[{}] for rentalRequest [{}]",
                saved.getId(), ref, rentalRequestId);
        return saved;
    }

    @Transactional
    public PaymentRequestResponse reportTransfer(String paymentRequestId, String actorId, ReportTransferRequest request) {
        PaymentRequest payment = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        if (!actorId.equals(payment.getPayerId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        if (payment.getStatus() == PaymentStatus.CONFIRMED) {
            throw new AppException(PaymentErrorCode.PAYMENT_ALREADY_CONFIRMED);
        }

        if (payment.getStatus() == PaymentStatus.TRANSFER_REPORTED) {
            // Idempotent return
            return toResponse(payment, actorId);
        }

        if (payment.getStatus() != PaymentStatus.AWAITING_TRANSFER && payment.getStatus() != PaymentStatus.REJECTED) {
            throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS);
        }

        // Chấp nhận 1 trong 2 nguồn chứng từ: mobile session hoặc web proofStorageId
        String proofStorageId;
        if (request != null && request.evidenceUploadSessionId() != null && !request.evidenceUploadSessionId().isBlank()) {
            proofStorageId = proofUploadSessionService.consumeSession(
                    request.evidenceUploadSessionId().trim(),
                    payment.getId(),
                    actorId
            );
        } else if (request != null && request.proofStorageId() != null && !request.proofStorageId().isBlank()) {
            proofStorageId = request.proofStorageId().trim();

            // Kiểm tra tính hợp lệ của file chứng từ nếu storageObjectRepository có sẵn
            if (storageObjectRepository != null) {
                StorageObject storageObj = storageObjectRepository.findById(proofStorageId)
                        .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID));

                if (!actorId.equals(storageObj.getOwnerId())) {
                    log.warn("Actor [{}] tried to use storage object [{}] owned by [{}]",
                            actorId, proofStorageId, storageObj.getOwnerId());
                    throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID);
                }
                if (storageObj.getStatus() != StorageStatus.READY) {
                    log.warn("Storage object [{}] is not READY (status={})", proofStorageId, storageObj.getStatus());
                    throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID);
                }
                if (storageObj.getReferenceId() != null
                        && !storageObj.getReferenceId().isBlank()
                        && !payment.getId().equals(storageObj.getReferenceId())
                        && !payment.getRentalRequestId().equals(storageObj.getReferenceId())) {
                    log.warn("Storage object [{}] referenceId [{}] does not match payment [{}] or rentalRequest [{}]",
                            proofStorageId, storageObj.getReferenceId(), payment.getId(), payment.getRentalRequestId());
                    throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID);
                }
            }
        } else {
            throw new AppException(PaymentErrorCode.PAYMENT_PROOF_REQUIRED);
        }

        PaymentStatus oldStatus = payment.getStatus();
        Instant now = Instant.now();

        payment.setStatus(PaymentStatus.TRANSFER_REPORTED);
        payment.setPayerReportedAt(now);
        payment.setConfirmationDueAt(now.plus(Duration.ofHours(confirmationWindowHours)));
        if (payment.getRejectedAt() != null || payment.getRejectedReason() != null) {
            payment.setRejectedAt(null);
            payment.setRejectedReason(null);
        }
        if (request.bankTransactionReference() != null && !request.bankTransactionReference().isBlank()) {
            payment.setBankTransactionReference(request.bankTransactionReference().trim());
        }

        PaymentEvidence evidence = PaymentEvidence.builder()
                .paymentRequestId(payment.getId())
                .uploadedBy(actorId)
                .storageObjectId(proofStorageId)
                .declaredTransferTime(request.declaredTransferTime() != null ? request.declaredTransferTime() : now)
                .bankTransactionReference(request.bankTransactionReference() != null ? request.bankTransactionReference().trim() : null)
                .payerAccountLast4(request.payerAccountLast4() != null ? request.payerAccountLast4().trim() : null)
                .note(request.note() != null ? request.note().trim() : null)
                .build();
        paymentEvidenceRepository.save(evidence);

        PaymentRequest saved = paymentRequestRepository.save(payment);

        recordEvent(saved.getId(), PaymentEventType.TRANSFER_REPORTED, oldStatus, PaymentStatus.TRANSFER_REPORTED,
                actorId, "TENANT", request.note(), null);

        log.info("Tenant [{}] reported transfer for PaymentRequest [{}] with proof [{}]",
                actorId, paymentRequestId, proofStorageId);
        return toResponse(saved, actorId);
    }

    @Transactional
    public PaymentRequestResponse confirmReceipt(String paymentRequestId, String actorId) {
        PaymentRequest payment = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        if (!actorId.equals(payment.getPayeeId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        if (payment.getStatus() == PaymentStatus.CONFIRMED) {
            return toResponse(payment, actorId);
        }

        if (payment.getStatus() != PaymentStatus.TRANSFER_REPORTED) {
            throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS);
        }

        Instant now = Instant.now();
        payment.setStatus(PaymentStatus.CONFIRMED);
        payment.setPayeeConfirmedAt(now);
        payment.setConfirmedAt(now);
        payment.setContractDueAt(now.plus(Duration.ofHours(contractDueHours)));

        PaymentRequest saved = paymentRequestRepository.save(payment);

        // Update DepositRecord to HELD
        depositRecordRepository.findByInitialPaymentRequestId(saved.getId()).ifPresent(record -> {
            record.setStatus(DepositStatus.HELD);
            record.setHeldAmount(record.getOriginalAmount());
            record.setReceivedConfirmedAt(now);
            depositRecordRepository.save(record);
        });

        recordEvent(saved.getId(), PaymentEventType.RECEIPT_CONFIRMED, PaymentStatus.TRANSFER_REPORTED,
                PaymentStatus.CONFIRMED, actorId, "LANDLORD", "Chủ nhà xác nhận đã nhận đủ tiền", null);

        log.info("Landlord [{}] CONFIRMED receipt for PaymentRequest [{}]", actorId, paymentRequestId);
        return toResponse(saved, actorId);
    }

    @Transactional
    public PaymentRequestResponse rejectReceipt(String paymentRequestId, String actorId, RejectReceiptRequest request) {
        PaymentRequest payment = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        if (!actorId.equals(payment.getPayeeId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        if (payment.getStatus() != PaymentStatus.TRANSFER_REPORTED) {
            throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS);
        }

        if (request.reason() == null || request.reason().isBlank()) {
            throw new AppException(PaymentErrorCode.REJECTION_REASON_REQUIRED);
        }

        Instant now = Instant.now();
        payment.setStatus(PaymentStatus.REJECTED);
        payment.setRejectedAt(now);
        payment.setRejectedReason(request.reason().trim());

        PaymentRequest saved = paymentRequestRepository.save(payment);

        recordEvent(saved.getId(), PaymentEventType.RECEIPT_REJECTED, PaymentStatus.TRANSFER_REPORTED,
                PaymentStatus.REJECTED, actorId, "LANDLORD", request.reason().trim(), null);

        log.info("Landlord [{}] REJECTED receipt for PaymentRequest [{}]: {}", actorId, paymentRequestId, request.reason());
        return toResponse(saved, actorId);
    }

    @Transactional
    public PaymentRequestResponse dispute(String paymentRequestId, String actorId, DisputePaymentRequest request) {
        PaymentRequest payment = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        if (!actorId.equals(payment.getPayerId()) && !actorId.equals(payment.getPayeeId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        if (payment.getStatus() != PaymentStatus.TRANSFER_REPORTED && payment.getStatus() != PaymentStatus.REJECTED) {
            throw new AppException(PaymentErrorCode.DISPUTE_NOT_ALLOWED);
        }

        PaymentStatus oldStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.DISPUTED);
        PaymentRequest saved = paymentRequestRepository.save(payment);

        String role = actorId.equals(payment.getPayerId()) ? "TENANT" : "LANDLORD";
        recordEvent(saved.getId(), PaymentEventType.DISPUTED, oldStatus, PaymentStatus.DISPUTED,
                actorId, role, request.reason().trim(), null);

        log.info("Actor [{}] [{}] opened DISPUTE on PaymentRequest [{}]", actorId, role, paymentRequestId);
        return toResponse(saved, actorId);
    }

    @Transactional(readOnly = true)
    public PaymentRequestResponse getPaymentRequest(String paymentRequestId, String actorId) {
        PaymentRequest payment = paymentRequestRepository.findById(paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        if (!actorId.equals(payment.getPayerId()) && !actorId.equals(payment.getPayeeId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        return toResponse(payment, actorId);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentRequest> findByRentalRequestIdAndType(String rentalRequestId, PaymentType type) {
        return paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId, type);
    }

    @Transactional(readOnly = true)
    public PaymentRequestResponse getInitialPaymentByRentalRequestId(String rentalRequestId, String actorId) {
        PaymentRequest payment = paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId, PaymentType.INITIAL)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        if (!actorId.equals(payment.getPayerId()) && !actorId.equals(payment.getPayeeId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        return toResponse(payment, actorId);
    }

    @Transactional(readOnly = true)
    public List<PaymentRequestResponse> getMyPaymentRequests(String actorId) {
        List<PaymentRequest> list = paymentRequestRepository.findByPayerIdOrPayeeIdOrderByCreatedAtDesc(actorId, actorId);
        return list.stream().map(p -> toResponse(p, actorId)).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentEventResponse> getEvents(String paymentRequestId, String actorId) {
        PaymentRequest payment = paymentRequestRepository.findById(paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        if (!actorId.equals(payment.getPayerId()) && !actorId.equals(payment.getPayeeId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        return paymentEventRepository.findByPaymentRequestIdOrderByCreatedAtAsc(paymentRequestId).stream()
                .map(e -> PaymentEventResponse.builder()
                        .id(e.getId())
                        .paymentRequestId(e.getPaymentRequestId())
                        .eventType(e.getEventType())
                        .fromStatus(e.getFromStatus())
                        .toStatus(e.getToStatus())
                        .actorId(e.getActorId())
                        .actorRole(e.getActorRole())
                        .reason(e.getReason())
                        .metadataJson(e.getMetadataJson())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional
    public void cancelPaymentByRentalRequest(String rentalRequestId, String reason) {
        paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId, PaymentType.INITIAL)
                .ifPresent(payment -> {
                    if (payment.getStatus() == PaymentStatus.AWAITING_TRANSFER) {
                        PaymentStatus oldStatus = payment.getStatus();
                        payment.setStatus(PaymentStatus.CANCELLED);
                        payment.setCancelledAt(Instant.now());
                        payment.setCancelledReason(reason);
                        paymentRequestRepository.save(payment);
                        recordEvent(payment.getId(), PaymentEventType.CANCELLED, oldStatus,
                                PaymentStatus.CANCELLED, "SYSTEM", "SYSTEM", reason, null);
                        log.info("PaymentRequest [{}] cancelled due to rental request cancellation: {}",
                                payment.getId(), reason);
                    }
                });
    }

    @Transactional
    public void handleHoldExpired(String rentalRequestId) {
        paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId, PaymentType.INITIAL)
                .ifPresent(payment -> {
                    Instant now = Instant.now();
                    if (payment.getStatus() == PaymentStatus.AWAITING_TRANSFER) {
                        PaymentStatus oldStatus = payment.getStatus();
                        payment.setStatus(PaymentStatus.EXPIRED);
                        payment.setExpiredAt(now);
                        paymentRequestRepository.save(payment);
                        recordEvent(payment.getId(), PaymentEventType.EXPIRED, oldStatus,
                                PaymentStatus.EXPIRED, "SYSTEM", "SYSTEM", "Hết hạn chuyển khoản giữ chỗ", null);
                        log.info("PaymentRequest [{}] marked EXPIRED because hold expired without payment", payment.getId());
                    } else if (payment.getStatus() == PaymentStatus.CONFIRMED) {
                        // Landlord failed to prepare contract on time.
                        // Create REFUND OBLIGATION PaymentRequest (LANDLORD -> TENANT), do NOT auto-refund!
                        log.info("Hold expired for CONFIRMED payment [{}]. Creating INITIAL_PAYMENT_REFUND obligation.", payment.getId());
                        createRefundObligation(payment);
                    }
                });
    }

    private void createRefundObligation(PaymentRequest initialPayment) {
        // Idempotency: check if refund request already created
        Optional<PaymentRequest> existingRefund = paymentRequestRepository.findByRentalRequestIdAndType(
                initialPayment.getRentalRequestId(), PaymentType.INITIAL_PAYMENT_REFUND);
        if (existingRefund.isPresent()) {
            return;
        }

        String ref = transferReferenceGenerator.generateUniqueReference();
        BankAccountSnapshotDto tenantAccount = parseSnapshot(initialPayment.getPayerBankAccountSnapshot());

        String qrUrl = "";
        if (tenantAccount != null && tenantAccount.bankBin() != null && tenantAccount.accountNumber() != null) {
            qrUrl = vietQrProvider.generateQrImageUrl(
                    tenantAccount.bankBin(),
                    tenantAccount.accountNumber(),
                    tenantAccount.accountHolderName(),
                    initialPayment.getTotalAmount(),
                    ref
            );
        }

        PaymentRequest refundPayment = PaymentRequest.builder()
                .rentalRequestId(initialPayment.getRentalRequestId())
                .listingId(initialPayment.getListingId())
                .payerId(initialPayment.getPayeeId()) // Landlord must refund
                .payeeId(initialPayment.getPayerId()) // Tenant receives refund
                .type(PaymentType.INITIAL_PAYMENT_REFUND)
                .direction(PaymentDirection.LANDLORD_TO_TENANT)
                .status(PaymentStatus.AWAITING_TRANSFER)
                .currency("VND")
                .totalAmount(initialPayment.getTotalAmount())
                .transferReference(ref)
                .payerBankAccountSnapshot(initialPayment.getPayeeBankAccountSnapshot())
                .payeeBankAccountSnapshot(initialPayment.getPayerBankAccountSnapshot())
                .qrProvider("VIETQR_QUICK_LINK")
                .qrImageUrl(qrUrl)
                .dueAt(Instant.now().plus(Duration.ofDays(3)))
                .build();

        PaymentRequest savedRefund = paymentRequestRepository.save(refundPayment);

        // Update DepositRecord status to REFUND_DUE
        depositRecordRepository.findByInitialPaymentRequestId(initialPayment.getId()).ifPresent(record -> {
            record.setStatus(DepositStatus.REFUND_DUE);
            record.setRefundPaymentRequestId(savedRefund.getId());
            depositRecordRepository.save(record);
        });

        recordEvent(savedRefund.getId(), PaymentEventType.REFUND_OBLIGATION_CREATED, null,
                PaymentStatus.AWAITING_TRANSFER, "SYSTEM", "SYSTEM",
                "Quá thời hạn tạo hợp đồng, phát sinh nghĩa vụ hoàn tiền từ chủ nhà cho người thuê", null);
    }

    @Transactional(readOnly = true)
    public boolean isHoldProtected(String rentalRequestId) {
        return paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId, PaymentType.INITIAL)
                .map(payment -> {
                    Instant now = Instant.now();
                    if (payment.getStatus() == PaymentStatus.CONFIRMED) {
                        return payment.getContractDueAt() == null || payment.getContractDueAt().isAfter(now);
                    }
                    if (payment.getStatus() == PaymentStatus.TRANSFER_REPORTED) {
                        return payment.getConfirmationDueAt() == null || payment.getConfirmationDueAt().isAfter(now);
                    }
                    if (payment.getStatus() == PaymentStatus.AWAITING_TRANSFER) {
                        return payment.getDueAt() == null || payment.getDueAt().isAfter(now);
                    }
                    return false;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isConfirmed(String rentalRequestId) {
        return paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId, PaymentType.INITIAL)
                .map(p -> p.getStatus() == PaymentStatus.CONFIRMED)
                .orElse(false);
    }

    public PaymentRequestResponse toResponse(PaymentRequest payment, String actorId) {
        List<PaymentLineItemResponse> lineItems = paymentLineItemRepository
                .findByPaymentRequestIdOrderBySortOrderAsc(payment.getId()).stream()
                .map(item -> PaymentLineItemResponse.builder()
                        .id(item.getId())
                        .type(item.getType())
                        .displayName(item.getDisplayName())
                        .amount(item.getAmount())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .calculationDescription(item.getCalculationDescription())
                        .note(item.getNote())
                        .sortOrder(item.getSortOrder())
                        .build())
                .toList();

        List<PaymentEvidenceResponse> evidences = paymentEvidenceRepository
                .findByPaymentRequestIdOrderByCreatedAtDesc(payment.getId()).stream()
                .map(ev -> PaymentEvidenceResponse.builder()
                        .id(ev.getId())
                        .uploadedBy(ev.getUploadedBy())
                        .storageObjectId(ev.getStorageObjectId())
                        .declaredTransferTime(ev.getDeclaredTransferTime())
                        .bankTransactionReference(ev.getBankTransactionReference())
                        .payerAccountLast4(ev.getPayerAccountLast4())
                        .note(ev.getNote())
                        .createdAt(ev.getCreatedAt())
                        .build())
                .toList();

        return PaymentRequestResponse.builder()
                .id(payment.getId())
                .rentalRequestId(payment.getRentalRequestId())
                .contractId(payment.getContractId())
                .listingId(payment.getListingId())
                .payerId(payment.getPayerId())
                .payeeId(payment.getPayeeId())
                .type(payment.getType())
                .direction(payment.getDirection())
                .status(payment.getStatus())
                .currency(payment.getCurrency())
                .totalAmount(payment.getTotalAmount())
                .transferReference(payment.getTransferReference())
                .bankTransactionReference(payment.getBankTransactionReference())
                .payerBankAccountSnapshot(parseSnapshot(payment.getPayerBankAccountSnapshot()))
                .payeeBankAccountSnapshot(parseSnapshot(payment.getPayeeBankAccountSnapshot()))
                .breakdownSnapshot(payment.getBreakdownSnapshot())
                .excludedChargesSnapshot(payment.getExcludedChargesSnapshot())
                .qrProvider(payment.getQrProvider())
                .qrImageUrl(payment.getQrImageUrl())
                .dueAt(payment.getDueAt())
                .payerReportedAt(payment.getPayerReportedAt())
                .payeeConfirmedAt(payment.getPayeeConfirmedAt())
                .confirmedAt(payment.getConfirmedAt())
                .confirmationDueAt(payment.getConfirmationDueAt())
                .contractDueAt(payment.getContractDueAt())
                .rejectedAt(payment.getRejectedAt())
                .rejectedReason(payment.getRejectedReason())
                .cancelledAt(payment.getCancelledAt())
                .cancelledReason(payment.getCancelledReason())
                .expiredAt(payment.getExpiredAt())
                .lineItems(lineItems)
                .evidences(evidences)
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }

    private void recordEvent(
            String paymentRequestId,
            PaymentEventType eventType,
            PaymentStatus fromStatus,
            PaymentStatus toStatus,
            String actorId,
            String actorRole,
            String reason,
            String metadataJson
    ) {
        PaymentEvent event = PaymentEvent.builder()
                .paymentRequestId(paymentRequestId)
                .eventType(eventType)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .actorId(actorId)
                .actorRole(actorRole)
                .reason(reason)
                .metadataJson(metadataJson)
                .createdAt(Instant.now())
                .build();
        paymentEventRepository.save(event);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize object to JSON", e);
            return "{}";
        }
    }

    public BankAccountSnapshotDto parseSnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, BankAccountSnapshotDto.class);
        } catch (Exception e) {
            log.warn("Failed to parse BankAccountSnapshotDto from JSON: {}", json);
            return null;
        }
    }
}
