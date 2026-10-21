package com.hs.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.qr.VietQrProvider;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
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
    private final BillingTime billingTime;

    @Value("${homespace.payment.confirmation-window-hours:24}")
    private long confirmationWindowHours = 24;

    @Value("${homespace.payment.contract-due-hours:48}")
    private long contractDueHours = 48;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.hs.listing.repository.RentalRequestRepository rentalRequests;

    public Instant businessNow() { return billingTime == null ? Instant.now() : billingTime.now(); }

    public static boolean isOnboarding(PaymentRequest p) {
        return p.getType()==PaymentType.INITIAL || p.getType()==PaymentType.RESERVATION_DEPOSIT;
    }

    /** Also called by mobile upload: UI countdowns never grant authority to pay. */
    public void validateTransferWindow(PaymentRequest p) {
        if (!isOnboarding(p)) return;
        Instant now=businessNow();
        if (p.getOpensAt()!=null && now.isBefore(p.getOpensAt()))
            throw new AppException(PaymentErrorCode.PAYMENT_HOLD_EXPIRED,"Chưa đến ngày thanh toán tiền thuê tháng đầu.");
        if (p.getDueAt()!=null && !now.isBefore(p.getDueAt()))
            throw new AppException(PaymentErrorCode.PAYMENT_HOLD_EXPIRED,"Đã hết hạn chuyển khoản; nếu đã chuyển tiền, hãy yêu cầu đối soát.");
        if (rentalRequests!=null) {
            var rental=rentalRequests.findById(p.getRentalRequestId()).orElseThrow();
            if (rental.getStatus()!=com.hs.listing.model.constant.RentalRequestStatus.ACCEPTED)
                throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS);
            if(rental.getCancellationRequestedAt()!=null)
                throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS,"Hồ sơ đang chờ quyết định hủy.");
        }
    }

    private void lockRentalForPayment(String id) {
        if(rentalRequests==null)return;
        var p=paymentRequestRepository.findById(id).orElseThrow(()->new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));
        if(isOnboarding(p))rentalRequests.findByIdForUpdate(p.getRentalRequestId()).orElseThrow();
    }

    @Transactional
    public PaymentRequest createDeposit(com.hs.listing.model.RentalRequest r, Instant due) {
        return createOnboarding(r.getId(),r.getListing().getId(),r.getRenterId(),r.getOwnerId(),
            BigDecimal.ZERO,r.getDepositAmount(),r.getCostBreakdownSnapshot(),r.getExcludedChargesSnapshot(),due,
            PaymentType.RESERVATION_DEPOSIT,null);
    }

    @Transactional
    public PaymentRequest createFirstRent(com.hs.listing.model.RentalRequest r, Instant opens, Instant due) {
        return createOnboarding(r.getId(),r.getListing().getId(),r.getRenterId(),r.getOwnerId(),
            r.getEffectiveMonthlyRent()!=null?r.getEffectiveMonthlyRent():r.getMonthlyRentPrice(),BigDecimal.ZERO,
            r.getCostBreakdownSnapshot(),r.getExcludedChargesSnapshot(),due,PaymentType.INITIAL,opens);
    }

    @Transactional
    public PaymentRequest createInitialPayment(
            String rentalRequestId,
            String listingId,
            String payerId,
            String payeeId,
            BigDecimal monthlyRent,
            BigDecimal depositAmount,
            String breakdownSnapshot,
            String excludedChargesSnapshot,
            Instant holdExpiresAt
    ) {
        return createOnboarding(rentalRequestId,listingId,payerId,payeeId,monthlyRent,depositAmount,
                breakdownSnapshot,excludedChargesSnapshot,holdExpiresAt,PaymentType.INITIAL,null);
    }

    private PaymentRequest createOnboarding(String rentalRequestId,String listingId,String payerId,String payeeId,
            BigDecimal monthlyRent,BigDecimal depositAmount,String breakdownSnapshot,String excludedChargesSnapshot,
            Instant holdExpiresAt,PaymentType type,Instant opensAt) {
        Optional<PaymentRequest> existing = paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId,type);
        if (existing.isPresent()) {
            return existing.get();
        }

        BigDecimal totalAmount = (monthlyRent != null ? monthlyRent : BigDecimal.ZERO)
                .add(depositAmount != null ? depositAmount : BigDecimal.ZERO);

        // Validate bank accounts of both parties
        BankAccount payeeAccount = bankAccountService.getDefaultIncomingAccount(payeeId);
        BankAccount payerAccount = bankAccountService.getDefaultRefundAccount(payerId);

        String ref = transferReferenceGenerator.generateUniqueReference();

        // Generate VietQR for tenant to transfer directly to landlord's incoming bank account
        String qrUrl = totalAmount.signum()==0 ? "" : vietQrProvider.generateQrImageUrl(
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
                .type(type)
                .onboardingKey(rentalRequestId+":"+type.name())
                .direction(PaymentDirection.TENANT_TO_LANDLORD)
                .status(totalAmount.signum()==0 ? PaymentStatus.CONFIRMED : PaymentStatus.AWAITING_TRANSFER)
                .confirmedAt(totalAmount.signum()==0 ? businessNow() : null)
                .opensAt(opensAt)
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

        // A zero deposit is a recorded exemption, never a zero-value bank transfer.
        if (depositAmount != null && (type==PaymentType.RESERVATION_DEPOSIT
                || type==PaymentType.INITIAL && paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId,PaymentType.RESERVATION_DEPOSIT).isEmpty())) {
            DepositRecord depositRecord = DepositRecord.builder()
                    .rentalRequestId(rentalRequestId)
                    .initialPaymentRequestId(saved.getId())
                    .landlordId(payeeId)
                    .tenantId(payerId)
                    .originalAmount(depositAmount)
                    .heldAmount(BigDecimal.ZERO)
                    .refundableAmount(depositAmount)
                    .status(depositAmount.signum()==0?DepositStatus.HELD:DepositStatus.PENDING)
                    .build();
            depositRecordRepository.save(depositRecord);
        }

        recordEvent(saved.getId(), PaymentEventType.CREATED, null, saved.getStatus(),
                payeeId, "LANDLORD", totalAmount.signum()==0?"Ghi nhận miễn thanh toán; không phát sinh chuyển khoản":"Tạo yêu cầu thanh toán "+type.name(), null);

        log.info("Created {} PaymentRequest [{}] ref=[{}] for rentalRequest [{}]",
                type, saved.getId(), ref, rentalRequestId);
        return saved;
    }

    /** One direct-transfer request per immutable issued invoice. */
    @Transactional
    public PaymentRequest createContractSettlement(String contractId,String rentalId,String listingId,String payerId,String payeeId,
            BigDecimal amount,Instant dueAt,boolean refund,String description) {
        if(amount==null || amount.signum()<=0 || amount.stripTrailingZeros().scale()>2 || dueAt==null) throw new IllegalArgumentException("Invalid settlement");
        PaymentType type=refund?PaymentType.DEPOSIT_REFUND:PaymentType.OTHER_CHARGE;
        Optional<PaymentRequest> existing=paymentRequestRepository.findByContractIdAndType(contractId,type);
        if(existing.isPresent()) {
            PaymentRequest old=existing.get();
            if(!payerId.equals(old.getPayerId()) || !payeeId.equals(old.getPayeeId()) || old.getTotalAmount().compareTo(amount)!=0)
                throw new IllegalStateException("Settlement already frozen");
            return old;
        }
        BankAccount bank=refund?bankAccountService.getDefaultRefundAccount(payeeId):bankAccountService.getDefaultIncomingAccount(payeeId);
        String reference=transferReferenceGenerator.generateUniqueReference();
        PaymentRequest payment=paymentRequestRepository.save(PaymentRequest.builder().contractId(contractId).rentalRequestId(rentalId).listingId(listingId)
                .payerId(payerId).payeeId(payeeId).type(type).direction(refund?PaymentDirection.LANDLORD_TO_TENANT:PaymentDirection.TENANT_TO_LANDLORD)
                .status(PaymentStatus.AWAITING_TRANSFER).currency("VND").totalAmount(amount).transferReference(reference).dueAt(dueAt)
                .payeeBankAccountSnapshot(toJson(new BankAccountSnapshotDto(bank.getBankBin(),bank.getBankCode(),bank.getBankName(),bank.getAccountNumber(),bank.getAccountHolderName())))
                .qrProvider("VIETQR_QUICK_LINK").qrImageUrl(vietQrProvider.generateQrImageUrl(bank.getBankBin(),bank.getAccountNumber(),bank.getAccountHolderName(),amount,reference))
                .build());
        paymentLineItemRepository.save(PaymentLineItem.builder().paymentRequestId(payment.getId()).type(type.name()).displayName(description)
                .quantity(1).unitPrice(amount).amount(amount).calculationDescription("Quyết toán được cả hai bên xác nhận").sortOrder(0).build());
        recordEvent(payment.getId(),PaymentEventType.CREATED,null,PaymentStatus.AWAITING_TRANSFER,"SYSTEM","SYSTEM",description,null);
        return payment;
    }

    /** Separate, idempotent delta obligation. Creating/signing an appendix is not receipt confirmation. */
    public void validateDepositAdjustmentRecipient(String payeeId,boolean refund) {
        if(refund)bankAccountService.getDefaultRefundAccount(payeeId);else bankAccountService.getDefaultIncomingAccount(payeeId);
    }

    @Transactional public PaymentRequest createDepositAdjustment(String amendmentId,String contractId,String rentalId,String listingId,
            String landlordId,String tenantId,BigDecimal delta,Instant dueAt) {
        if(delta==null || delta.signum()==0 || delta.stripTrailingZeros().scale()>0 || delta.abs().compareTo(new BigDecimal("1000000000"))>0)throw new IllegalArgumentException("Invalid deposit delta");
        var existing=paymentRequestRepository.findByInvoiceId(amendmentId);
        if(existing.isPresent()) {
            var p=existing.get();
            if(p.getType()!=PaymentType.DEPOSIT_ADJUSTMENT || !contractId.equals(p.getContractId()) || p.getTotalAmount().compareTo(delta.abs())!=0
                    || (p.getDirection()==PaymentDirection.TENANT_TO_LANDLORD)!=(delta.signum()>0))throw new IllegalStateException("Deposit adjustment already frozen");
            return p;
        }
        boolean refund=delta.signum()<0;String payee=refund?tenantId:landlordId;
        BankAccount bank=refund?bankAccountService.getDefaultRefundAccount(payee):bankAccountService.getDefaultIncomingAccount(payee);
        String reference=transferReferenceGenerator.generateUniqueReference();
        String description=refund?"Hoàn chênh lệch cọc theo phụ lục":"Nộp bổ sung cọc theo phụ lục";
        var payment=paymentRequestRepository.save(PaymentRequest.builder().invoiceId(amendmentId).contractId(contractId).rentalRequestId(rentalId).listingId(listingId)
                .payerId(refund?landlordId:tenantId).payeeId(payee).type(PaymentType.DEPOSIT_ADJUSTMENT)
                .direction(refund?PaymentDirection.LANDLORD_TO_TENANT:PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.AWAITING_TRANSFER)
                .currency("VND").totalAmount(delta.abs()).transferReference(reference).dueAt(dueAt)
                .payeeBankAccountSnapshot(toJson(new BankAccountSnapshotDto(bank.getBankBin(),bank.getBankCode(),bank.getBankName(),bank.getAccountNumber(),bank.getAccountHolderName())))
                .qrProvider("VIETQR_QUICK_LINK").qrImageUrl(vietQrProvider.generateQrImageUrl(bank.getBankBin(),bank.getAccountNumber(),bank.getAccountHolderName(),delta.abs(),reference)).build());
        paymentLineItemRepository.save(PaymentLineItem.builder().paymentRequestId(payment.getId()).type("DEPOSIT_ADJUSTMENT").displayName(description)
                .quantity(1).unitPrice(delta.abs()).amount(delta.abs()).calculationDescription("Phần chênh lệch cọc; không thu lại toàn bộ cọc").sortOrder(0).build());
        recordEvent(payment.getId(),PaymentEventType.CREATED,null,PaymentStatus.AWAITING_TRANSFER,"SYSTEM","SYSTEM",description,null);
        return payment;
    }

    @Transactional public void syncContractSettlementDeadline(String id,Instant now) {
        PaymentRequest p=paymentRequestRepository.findByIdForUpdate(id).orElseThrow();
        if((p.getType()==PaymentType.DEPOSIT_REFUND || p.getType()==PaymentType.DEPOSIT_ADJUSTMENT || p.getType()==PaymentType.OTHER_CHARGE && p.getContractId()!=null)
                && p.getDueAt()!=null && now.isAfter(p.getDueAt())
                && (p.getStatus()==PaymentStatus.AWAITING_TRANSFER || p.getStatus()==PaymentStatus.REJECTED)) {
            PaymentStatus before=p.getStatus(); p.setStatus(PaymentStatus.OVERDUE); paymentRequestRepository.save(p);
            recordEvent(id,PaymentEventType.OVERDUE,before,PaymentStatus.OVERDUE,"SYSTEM","SYSTEM","Khoản quyết toán/hoàn cọc quá hạn, nghĩa vụ thanh toán vẫn còn",null);
        }
    }

    @Transactional
    public PaymentRequest createMonthlyPayment(String invoiceId, String contractId,
            String rentalRequestId, String listingId, String payerId, String payeeId,
            BigDecimal totalAmount, String breakdownSnapshot, Instant dueAt) {
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("A monthly payment must have a positive amount");
        }
        Optional<PaymentRequest> existing = paymentRequestRepository.findByInvoiceId(invoiceId);
        if (existing.isPresent()) return existing.get();

        BankAccount payeeAccount = bankAccountService.getDefaultIncomingAccount(payeeId);
        String reference = transferReferenceGenerator.generateUniqueReference();
        PaymentRequest payment = paymentRequestRepository.save(PaymentRequest.builder()
                .invoiceId(invoiceId).contractId(contractId).rentalRequestId(rentalRequestId)
                .listingId(listingId).payerId(payerId).payeeId(payeeId)
                .type(PaymentType.MONTHLY_RENT).direction(PaymentDirection.TENANT_TO_LANDLORD)
                .status(PaymentStatus.AWAITING_TRANSFER).currency("VND")
                .totalAmount(totalAmount).transferReference(reference)
                .payeeBankAccountSnapshot(toJson(new BankAccountSnapshotDto(
                        payeeAccount.getBankBin(), payeeAccount.getBankCode(), payeeAccount.getBankName(),
                        payeeAccount.getAccountNumber(), payeeAccount.getAccountHolderName())))
                .breakdownSnapshot(breakdownSnapshot).dueAt(dueAt)
                .qrProvider("VIETQR_QUICK_LINK")
                .qrImageUrl(vietQrProvider.generateQrImageUrl(payeeAccount.getBankBin(),
                        payeeAccount.getAccountNumber(), payeeAccount.getAccountHolderName(), totalAmount, reference))
                .build());
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(breakdownSnapshot,
                    new TypeReference<List<Map<String, Object>>>() {});
            int sort = 0;
            for (Map<String, Object> row : rows) {
                BigDecimal amount = new BigDecimal(String.valueOf(row.get("amount")));
                paymentLineItemRepository.save(PaymentLineItem.builder()
                        .paymentRequestId(payment.getId())
                        .type(String.valueOf(row.get("type")))
                        .displayName(String.valueOf(row.get("description")))
                        .amount(amount)
                        .quantity(1)
                        .unitPrice(amount)
                        .calculationDescription(row.get("quantity") + " × " + row.get("unitPrice"))
                        .sortOrder(sort++).build());
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid monthly invoice line item snapshot", ex);
        }
        recordEvent(payment.getId(), PaymentEventType.CREATED, null,
                PaymentStatus.AWAITING_TRANSFER, "SYSTEM", "SYSTEM", "Tạo thanh toán hóa đơn tháng", null);
        return payment;
    }

    /** Re-price only an unreported monthly transfer. The same reference is retained and QR is regenerated. */
    @Transactional
    public boolean increaseMonthlyPayment(String invoiceId, BigDecimal newTotal, String breakdownSnapshot) {
        PaymentRequest found = paymentRequestRepository.findByInvoiceId(invoiceId).orElse(null);
        if (found == null) return false;
        PaymentRequest payment = paymentRequestRepository.findByIdForUpdate(found.getId()).orElse(null);
        if (payment == null || payment.getType() != PaymentType.MONTHLY_RENT
                || !List.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE,
                        PaymentStatus.REJECTED).contains(payment.getStatus())) return false;
        if (newTotal == null || newTotal.compareTo(payment.getTotalAmount()) <= 0) return false;
        try {
            BankAccountSnapshotDto bank = objectMapper.readValue(payment.getPayeeBankAccountSnapshot(),
                    BankAccountSnapshotDto.class);
            List<Map<String, Object>> rows = objectMapper.readValue(breakdownSnapshot,
                    new TypeReference<List<Map<String, Object>>>() {});
            paymentLineItemRepository.deleteAll(paymentLineItemRepository
                    .findByPaymentRequestIdOrderBySortOrderAsc(payment.getId()));
            int sort = 0;
            for (Map<String, Object> row : rows) {
                BigDecimal amount = new BigDecimal(String.valueOf(row.get("amount")));
                paymentLineItemRepository.save(PaymentLineItem.builder()
                        .paymentRequestId(payment.getId()).type(String.valueOf(row.get("type")))
                        .displayName(String.valueOf(row.get("description")))
                        .amount(amount).quantity(1).unitPrice(amount)
                        .calculationDescription(row.get("quantity") + " × " + row.get("unitPrice"))
                        .sortOrder(sort++).build());
            }
            BigDecimal previous = payment.getTotalAmount();
            payment.setTotalAmount(newTotal);
            payment.setBreakdownSnapshot(breakdownSnapshot);
            payment.setQrImageUrl(vietQrProvider.generateQrImageUrl(bank.bankBin(), bank.accountNumber(),
                    bank.accountHolderName(), newTotal, payment.getTransferReference()));
            paymentRequestRepository.save(payment);
            recordEvent(payment.getId(), PaymentEventType.AMOUNT_ADJUSTED,
                    payment.getStatus(), payment.getStatus(), "SYSTEM", "SYSTEM",
                    "Phí chậm thanh toán: " + previous + " -> " + newTotal, null);
            return true;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot adjust monthly payment", ex);
        }
    }

    /** Retire the old QR before carrying its full balance to the next invoice. */
    @Transactional
    public void cancelMonthlyForRollover(String invoiceId) {
        PaymentRequest found = paymentRequestRepository.findByInvoiceId(invoiceId)
                .orElseThrow(() -> new IllegalStateException("Monthly payment missing for rollover"));
        PaymentRequest payment = paymentRequestRepository.findByIdForUpdate(found.getId())
                .orElseThrow(() -> new IllegalStateException("Monthly payment missing for rollover"));
        if (payment.getType() != PaymentType.MONTHLY_RENT || !List.of(
                PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE, PaymentStatus.REJECTED)
                .contains(payment.getStatus()))
            throw new IllegalStateException("Monthly payment cannot be rolled over while under review or settled");
        PaymentStatus old = payment.getStatus();
        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setCancelledAt(billingTime.now());
        payment.setCancelledReason("Công nợ đã chuyển sang hóa đơn kỳ kế tiếp");
        payment.setQrImageUrl(null);
        paymentRequestRepository.save(payment);
        recordEvent(payment.getId(), PaymentEventType.CANCELLED, old, PaymentStatus.CANCELLED,
                "SYSTEM", "SYSTEM", payment.getCancelledReason(), null);
    }

    @Transactional
    public PaymentRequestResponse reportTransfer(String paymentRequestId, String actorId, ReportTransferRequest request) {
        lockRentalForPayment(paymentRequestId);
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

        if (payment.getStatus() != PaymentStatus.AWAITING_TRANSFER && payment.getStatus() != PaymentStatus.REJECTED
                && !(PaymentTimePolicy.usesBillingTime(payment) && payment.getStatus() == PaymentStatus.OVERDUE)) {
            throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS);
        }
        if (PaymentTimePolicy.usesBillingTime(payment) && !isOnboarding(payment)
                && (request == null || request.expectedAmount() == null
                || request.expectedAmount().compareTo(payment.getTotalAmount()) != 0)) {
            throw new AppException(PaymentErrorCode.MONTHLY_AMOUNT_CHANGED);
        }

        validateTransferWindow(payment);
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
                if (storageObj.getVisibility() != StorageVisibility.PRIVATE) {
                    log.warn("Storage object [{}] is not PRIVATE (visibility={})", proofStorageId, storageObj.getVisibility());
                    throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID);
                }
                if (storageObj.getPurpose() != null && storageObj.getPurpose() != StoragePurpose.PAYMENT_PROOF) {
                    log.warn("Storage object [{}] purpose [{}] is not PAYMENT_PROOF", proofStorageId, storageObj.getPurpose());
                    throw new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID);
                }
                if (storageObj.getReferenceType() != null && !storageObj.getReferenceType().isBlank()
                        && !"PAYMENT_REQUEST".equalsIgnoreCase(storageObj.getReferenceType())) {
                    log.warn("Storage object [{}] referenceType [{}] is not PAYMENT_REQUEST", proofStorageId, storageObj.getReferenceType());
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
        Instant now = PaymentTimePolicy.usesBillingTime(payment) ? businessNow() : Instant.now();

        payment.setStatus(PaymentStatus.TRANSFER_REPORTED);
        payment.setPayerReportedAt(now);
        payment.setConfirmationDueAt(now.plus(Duration.ofHours(confirmationWindowHours)));
        if (payment.getRejectedAt() != null || payment.getRejectedReason() != null) {
            payment.setRejectedAt(null);
            payment.setRejectedReason(null);
        }

        PaymentEvidence evidence = PaymentEvidence.builder()
                .paymentRequestId(payment.getId())
                .uploadedBy(actorId)
                .storageObjectId(proofStorageId)
                .declaredTransferTime(now)
                .bankTransactionReference(null)
                .payerAccountLast4(null)
                .note(null)
                .build();
        paymentEvidenceRepository.save(evidence);

        PaymentRequest saved = paymentRequestRepository.save(payment);

        recordEvent(saved.getId(), PaymentEventType.TRANSFER_REPORTED, oldStatus, PaymentStatus.TRANSFER_REPORTED,
                actorId, payment.getDirection()==PaymentDirection.LANDLORD_TO_TENANT?"LANDLORD":"TENANT",
                payment.getDirection()==PaymentDirection.LANDLORD_TO_TENANT?"Chủ nhà đã gửi chứng từ hoàn tiền":"Người thuê đã gửi chứng từ chuyển khoản.", null);
        if(payment.getType()==PaymentType.DEPOSIT_REFUND) depositRecordRepository.findByRefundPaymentRequestId(saved.getId()).ifPresent(d->{
            d.setStatus(DepositStatus.REFUND_REPORTED); depositRecordRepository.save(d);
        });

        log.info("Payer [{}] reported transfer for PaymentRequest [{}] with proof [{}]",
                actorId, paymentRequestId, proofStorageId);
        return toResponse(saved, actorId);
    }

    @Transactional
    public PaymentRequestResponse confirmReceipt(String paymentRequestId, String actorId) {
        lockRentalForPayment(paymentRequestId);
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

        Instant now = PaymentTimePolicy.usesBillingTime(payment) ? businessNow() : Instant.now();
        payment.setStatus(PaymentStatus.CONFIRMED);
        payment.setPayeeConfirmedAt(now);
        payment.setConfirmedAt(now);
        if (payment.getType() == PaymentType.INITIAL) {
            payment.setContractDueAt(now.plus(Duration.ofHours(contractDueHours)));
        }

        PaymentRequest saved = paymentRequestRepository.save(payment);

        if(saved.getType()==PaymentType.DEPOSIT_ADJUSTMENT) {
            var deposit=depositRecordRepository.findByRentalRequestIdForUpdate(saved.getRentalRequestId()).orElseThrow(()->new IllegalStateException("Deposit record missing"));
            if(deposit.getStatus()!=DepositStatus.HELD)throw new IllegalStateException("Deposit is not held");
            BigDecimal delta=saved.getDirection()==PaymentDirection.TENANT_TO_LANDLORD?saved.getTotalAmount():saved.getTotalAmount().negate();
            BigDecimal held=deposit.getHeldAmount().add(delta);
            if(held.signum()<0)throw new IllegalStateException("Deposit refund exceeds actual held deposit");
            deposit.setHeldAmount(held);deposit.setRefundableAmount(held);depositRecordRepository.save(deposit);
        }

        // Update DepositRecord to HELD
        depositRecordRepository.findByInitialPaymentRequestId(saved.getId()).ifPresent(record -> {
            record.setStatus(DepositStatus.HELD);
            record.setHeldAmount(record.getOriginalAmount());
            record.setReceivedConfirmedAt(now);
            depositRecordRepository.save(record);
        });

        depositRecordRepository.findByRefundPaymentRequestId(saved.getId()).ifPresent(record -> {
            record.setStatus(DepositStatus.REFUNDED); record.setHeldAmount(BigDecimal.ZERO); record.setRefundableAmount(BigDecimal.ZERO);
            depositRecordRepository.save(record);
        });

        recordEvent(saved.getId(), PaymentEventType.RECEIPT_CONFIRMED, PaymentStatus.TRANSFER_REPORTED,
                PaymentStatus.CONFIRMED, actorId, payment.getDirection()==PaymentDirection.LANDLORD_TO_TENANT?"TENANT":"LANDLORD",
                payment.getDirection()==PaymentDirection.LANDLORD_TO_TENANT?"Người thuê xác nhận đã nhận tiền hoàn":"Chủ nhà xác nhận đã nhận đủ tiền", null);

        log.info("Payee [{}] CONFIRMED receipt for PaymentRequest [{}]", actorId, paymentRequestId);
        return toResponse(saved, actorId);
    }

    @Transactional
    public PaymentRequestResponse rejectReceipt(String paymentRequestId, String actorId, RejectReceiptRequest request) {
        lockRentalForPayment(paymentRequestId);
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

        Instant now = PaymentTimePolicy.usesBillingTime(payment) ? businessNow() : Instant.now();
        payment.setStatus(PaymentStatus.REJECTED);
        payment.setRejectedAt(now);
        payment.setRejectedReason(request.reason().trim());

        PaymentRequest saved = paymentRequestRepository.save(payment);

        recordEvent(saved.getId(), PaymentEventType.RECEIPT_REJECTED, PaymentStatus.TRANSFER_REPORTED,
                PaymentStatus.REJECTED, actorId, payment.getDirection()==PaymentDirection.LANDLORD_TO_TENANT?"TENANT":"LANDLORD", request.reason().trim(), null);
        if(payment.getType()==PaymentType.DEPOSIT_REFUND) depositRecordRepository.findByRefundPaymentRequestId(saved.getId()).ifPresent(d->{
            d.setStatus(DepositStatus.REFUND_DUE); depositRecordRepository.save(d);
        });

        log.info("Payee [{}] REJECTED receipt for PaymentRequest [{}]: {}", actorId, paymentRequestId, request.reason());
        return toResponse(saved, actorId);
    }

    @Transactional
    public PaymentRequestResponse dispute(String paymentRequestId, String actorId, DisputePaymentRequest request) {
        lockRentalForPayment(paymentRequestId);
        PaymentRequest payment = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        if (!actorId.equals(payment.getPayerId()) && !actorId.equals(payment.getPayeeId())) {
            throw new AppException(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN);
        }

        if (payment.getStatus() != PaymentStatus.TRANSFER_REPORTED && payment.getStatus() != PaymentStatus.REJECTED
                && !(isOnboarding(payment) && (payment.getStatus()==PaymentStatus.AWAITING_TRANSFER || payment.getStatus()==PaymentStatus.EXPIRED))) {
            throw new AppException(PaymentErrorCode.DISPUTE_NOT_ALLOWED);
        }

        PaymentStatus oldStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.DISPUTED);
        PaymentRequest saved = paymentRequestRepository.save(payment);

        boolean reverse=payment.getDirection()==PaymentDirection.LANDLORD_TO_TENANT;
        String role = actorId.equals(payment.getPayerId()) ? (reverse?"LANDLORD":"TENANT") : (reverse?"TENANT":"LANDLORD");
        recordEvent(saved.getId(), PaymentEventType.DISPUTED, oldStatus, PaymentStatus.DISPUTED,
                actorId, role, request.reason().trim(), null);
        if(payment.getType()==PaymentType.DEPOSIT_REFUND) depositRecordRepository.findByRefundPaymentRequestId(saved.getId()).ifPresent(d->{
            d.setStatus(DepositStatus.DISPUTED); depositRecordRepository.save(d);
        });

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
                .or(() -> paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId,PaymentType.RESERVATION_DEPOSIT))
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
        java.util.stream.Stream.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT)
                .map(t->paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId,t))
                .flatMap(Optional::stream).forEach(payment -> {
                    if (payment.getStatus() == PaymentStatus.AWAITING_TRANSFER) {
                        PaymentStatus oldStatus = payment.getStatus();
                        payment.setStatus(PaymentStatus.CANCELLED);
                        payment.setCancelledAt(businessNow());
                        payment.setCancelledReason(reason);
                        paymentRequestRepository.save(payment);
                        recordEvent(payment.getId(), PaymentEventType.CANCELLED, oldStatus,
                                PaymentStatus.CANCELLED, "SYSTEM", "SYSTEM", reason, null);
                        log.info("PaymentRequest [{}] cancelled due to rental request cancellation: {}",
                                payment.getId(), reason);
                    }
                });
    }

    /** No transfer declaration or positive receipt may be discarded by an immediate cancellation. */
    public static boolean cancellableBeforeTransfer(PaymentRequest p) {
        if(p.getPayerReportedAt()!=null || (p.getPayeeConfirmedAt()!=null || p.getConfirmedAt()!=null)
                && p.getTotalAmount()!=null && p.getTotalAmount().signum()>0)return false;
        return p.getStatus()==PaymentStatus.AWAITING_TRANSFER || p.getStatus()==PaymentStatus.EXPIRED
            || p.getStatus()==PaymentStatus.CANCELLED || p.getStatus()==PaymentStatus.OVERDUE
            || p.getStatus()==PaymentStatus.CONFIRMED && p.getType()==PaymentType.RESERVATION_DEPOSIT
                && p.getTotalAmount()!=null && p.getTotalAmount().signum()==0;
    }

    @Transactional
    public void handleHoldExpired(String rentalRequestId) {
        java.util.stream.Stream.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT)
                .map(t->paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId,t))
                .flatMap(Optional::stream).forEach(payment -> {
                    Instant now = businessNow();
                    if (payment.getStatus() == PaymentStatus.AWAITING_TRANSFER) {
                        PaymentStatus oldStatus = payment.getStatus();
                        payment.setStatus(PaymentStatus.EXPIRED);
                        payment.setExpiredAt(now);
                        paymentRequestRepository.save(payment);
                        recordEvent(payment.getId(), PaymentEventType.EXPIRED, oldStatus,
                                PaymentStatus.EXPIRED, "SYSTEM", "SYSTEM", "Hết hạn chuyển khoản giữ chỗ", null);
                        log.info("PaymentRequest [{}] marked EXPIRED because hold expired without payment", payment.getId());
                    }
                });
    }

    @Transactional
    public void createRefundObligation(PaymentRequest initialPayment) {
        createRefundObligation(initialPayment,initialPayment.getTotalAmount(),false);
    }

    /** Refund only confirmed rent when a separately agreed deposit is retained. Never alter the receipt. */
    @Transactional
    public void createRentRefundObligation(PaymentRequest initialPayment,BigDecimal amount) {
        createRefundObligation(initialPayment,amount,true);
    }

    private void createRefundObligation(PaymentRequest initialPayment,BigDecimal amount,boolean rentOnly) {
        if(amount==null || amount.signum()<0 || amount.compareTo(initialPayment.getTotalAmount())>0)
            throw new IllegalArgumentException("Invalid refund amount");
        if(amount.signum()==0)return;
        if(initialPayment.getStatus()!=PaymentStatus.CONFIRMED || initialPayment.getTotalAmount().signum()==0)return;
        // Idempotency: check if refund request already created
        Optional<PaymentRequest> existingRefund = paymentRequestRepository.findByOnboardingKey("REFUND:"+initialPayment.getId());
        if (existingRefund.isPresent()) {
            if(existingRefund.get().getTotalAmount().compareTo(amount)!=0)throw new IllegalStateException("Refund obligation amount differs; reconciliation required");
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
                    amount,
                    ref
            );
        }

        PaymentRequest refundPayment = PaymentRequest.builder()
                .contractId(initialPayment.getContractId())
                .rentalRequestId(initialPayment.getRentalRequestId())
                .listingId(initialPayment.getListingId())
                .payerId(initialPayment.getPayeeId()) // Landlord must refund
                .payeeId(initialPayment.getPayerId()) // Tenant receives refund
                .type(PaymentType.INITIAL_PAYMENT_REFUND)
                .onboardingKey("REFUND:"+initialPayment.getId())
                .direction(PaymentDirection.LANDLORD_TO_TENANT)
                .status(PaymentStatus.AWAITING_TRANSFER)
                .currency("VND")
                .totalAmount(amount)
                .transferReference(ref)
                .payerBankAccountSnapshot(initialPayment.getPayeeBankAccountSnapshot())
                .payeeBankAccountSnapshot(initialPayment.getPayerBankAccountSnapshot())
                .qrProvider("VIETQR_QUICK_LINK")
                .qrImageUrl(qrUrl)
                .dueAt(businessNow().plus(Duration.ofDays(3)))
                .build();

        PaymentRequest savedRefund = paymentRequestRepository.save(refundPayment);

        // Update DepositRecord status to REFUND_DUE
        if(!rentOnly) depositRecordRepository.findByInitialPaymentRequestId(initialPayment.getId()).ifPresent(record -> {
            record.setStatus(DepositStatus.REFUND_DUE);
            record.setRefundPaymentRequestId(savedRefund.getId());
            depositRecordRepository.save(record);
        });

        recordEvent(savedRefund.getId(), PaymentEventType.REFUND_OBLIGATION_CREATED, null,
                PaymentStatus.AWAITING_TRANSFER, "SYSTEM", "SYSTEM",
                rentOnly?"Hoàn tiền thuê đã nhận; cọc được xử lý riêng theo thông báo vi phạm":"Hai bên đồng ý hủy hồ sơ trước khi ký; chủ nhà cần hoàn khoản thực nhận và người thuê xác nhận nhận đủ", null);
    }

    @Transactional
    public void attachOnboardingToContract(String rentalId,String contractId) {
        for(var p:paymentRequestRepository.findByRentalRequestIdAndTypeIn(rentalId,
                List.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT,PaymentType.INITIAL_PAYMENT_REFUND))) {
            if(p.getContractId()!=null && !contractId.equals(p.getContractId()))throw new IllegalStateException("Payment already linked");
            if(p.getContractId()==null){p.setContractId(contractId);paymentRequestRepository.save(p);}
        }
    }

    @Transactional(readOnly = true)
    public boolean isHoldProtected(String rentalRequestId) {
        return java.util.stream.Stream.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT)
            .map(t->paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId,t))
            .flatMap(Optional::stream).anyMatch(p-> switch(p.getStatus()) {
                case CONFIRMED,TRANSFER_REPORTED,DISPUTED,REJECTED -> true;
                case AWAITING_TRANSFER -> p.getDueAt()==null || businessNow().isBefore(p.getDueAt());
                default -> false;
            });
    }

    @Transactional(readOnly = true)
    public boolean isConfirmed(String rentalRequestId) {
        return java.util.stream.Stream.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT)
                .map(t->paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId,t))
                .flatMap(Optional::stream).anyMatch(p->p.getStatus()==PaymentStatus.CONFIRMED);
    }

    @Transactional(readOnly = true)
    public List<PaymentRequestResponse> getContractPaymentRequests(String contractId, String actorId) {
        return paymentRequestRepository.findForContractAndActor(contractId, actorId).stream()
                .map(payment -> toResponse(payment, actorId)).toList();
    }

    @Transactional
    public void markMonthlyOverdue(String invoiceId, Instant now) {
        paymentRequestRepository.findByInvoiceId(invoiceId).ifPresent(payment -> {
            if (payment.getDueAt() != null && payment.getDueAt().isBefore(now)
                    && payment.getStatus() == PaymentStatus.AWAITING_TRANSFER) {
                payment.setStatus(PaymentStatus.OVERDUE);
                paymentRequestRepository.save(payment);
                recordEvent(payment.getId(), PaymentEventType.OVERDUE,
                        PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE,
                        "SYSTEM", "SYSTEM", "Hóa đơn tháng đã quá hạn", null);
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<PaymentRequest> findByInvoiceId(String invoiceId) {
        return paymentRequestRepository.findByInvoiceId(invoiceId);
    }

    /** Serialize billing decisions against proof submission/receipt confirmation. */
    @Transactional
    public Optional<PaymentRequest> lockMonthlyPayment(String invoiceId) {
        return paymentRequestRepository.findByInvoiceIdForUpdate(invoiceId);
    }

    @Transactional
    public void extendMonthlyDueDate(String invoiceId, Instant newDueAt, String actorId) {
        PaymentRequest payment = lockMonthlyPayment(invoiceId)
                .orElseThrow(() -> new IllegalStateException("Monthly payment missing"));
        if (payment.getType() != PaymentType.MONTHLY_RENT || !List.of(PaymentStatus.AWAITING_TRANSFER,
                PaymentStatus.OVERDUE, PaymentStatus.REJECTED).contains(payment.getStatus())) {
            throw new IllegalStateException("Payment is already reported, disputed or settled");
        }
        Instant previousDue = payment.getDueAt();
        PaymentStatus previousStatus = payment.getStatus();
        payment.setDueAt(newDueAt);
        payment.setStatus(PaymentStatus.AWAITING_TRANSFER);
        paymentRequestRepository.save(payment);
        recordEvent(payment.getId(), PaymentEventType.DUE_DATE_EXTENDED, previousStatus,
                payment.getStatus(), actorId, "LANDLORD", "Dời hạn thanh toán: " + previousDue + " -> " + newDueAt, null);
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
                .invoiceId(payment.getInvoiceId())
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
