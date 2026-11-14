package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.billing.*;
import com.hs.contract.notification.InvoiceNotificationWriter;
import com.hs.contract.notification.InvoiceNotificationWriter.Event;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.listing.dto.estimate.ExcludedChargeItem;
import com.hs.listing.service.RentalCostCalculator;
import com.hs.listing.service.ListingStatusService;
import com.hs.listing.service.ParkingReservationService;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.DepositRecord;
import com.hs.payment.model.constant.DepositStatus;
import com.hs.payment.repository.DepositRecordRepository;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.dto.PaymentRequestResponse;
import com.hs.payment.service.PaymentRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.math.RoundingMode;

@Service @RequiredArgsConstructor @Slf4j
public class MonthlyBillingService {
    @org.springframework.beans.factory.annotation.Autowired
    private ContractLifecycleService lifecycle;
    @org.springframework.beans.factory.annotation.Autowired
    private ContractTermsResolver terms;
    @org.springframework.beans.factory.annotation.Autowired
    private ContractAmendmentService amendments;
    private static final Set<OverdueAction.Type> RECORDED_BY_LANDLORD = EnumSet.of(OverdueAction.Type.PAYMENT_REQUEST,
            OverdueAction.Type.EXTENSION_PROPOSAL, OverdueAction.Type.MUTUAL_TERMINATION_PROPOSAL, OverdueAction.Type.LEGAL_REVIEW);
    private static final DateTimeFormatter BILLING_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final ContractRepository contracts;
    private final ContractRevisionRepository revisions;
    private final RentalRequestRepository rentalRequests;
    private final MonthlyInvoiceRepository invoices;
    private final PaymentRequestService payments;
    private final ObjectMapper mapper;
    private final BillingTime time;
    private final PlatformTransactionManager transactionManager;
    private final ListingStatusService listingStatusService;
    private final DepositRecordRepository deposits;
    private final ParkingReservationService parkingReservations;
    private final InvoiceNotificationWriter notifications;
    @org.springframework.beans.factory.annotation.Autowired private com.hs.contract.notification.TerminationNotificationWriter terminationNotices;
    @Value("${homespace.billing.extension-response-hours:48}")
    private int extensionResponseHours = 48;

    @Transactional
    public MonthlyInvoiceResponse requestPaymentExtension(String invoiceId, String tenantId, PaymentExtensionPayload payload) {
        validateExtension(payload);
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), tenantId);
        if (!tenantId.equals(contract.getTenantId())) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        validateExtensionClock(invoice);
        reconcile(invoice);
        if (!canRequestExtension(contract, invoice)) throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED);
        // Reconcile settles fees up to the request instant BEFORE the pause starts.
        List<PaymentExtension> history = new ArrayList<>(extensions(invoice));
        history.add(new PaymentExtension(UUID.randomUUID().toString(), payload.days(), payload.note().trim(),
                PaymentExtension.Status.PENDING, time.now(), time.now().plus(Duration.ofHours(Math.max(1, extensionResponseHours))),
                tenantId, invoice.getDueAt(), fee(invoice), null, null, null));
        invoice.setExtensionRequestsSnapshot(write(history));
        invoices.save(invoice);
        notifications.enqueueLandlord(contract, invoice, Event.INVOICE_EXTENSION_REQUESTED);
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse decidePaymentExtension(String invoiceId, String requestId, String landlordId, boolean approve) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), landlordId);
        requireLandlord(contract, landlordId);
        validateExtensionClock(invoice);
        reconcile(invoice); // Expire pending requests even if the scheduler has not run.
        PaymentExtension pending = pendingExtension(invoice).filter(r -> r.id().equals(requestId))
                .orElseThrow(() -> new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_FOUND));
        if (!billable(contract) || !unsettledAndUnreported(invoiceId)
                || invoice.getDeferredAt() != null || activeTerminationProposal(contract) || extensionActive(invoice))
            throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED);
        if (approve) approveExtension(invoice, pending, landlordId);
        else {
            replaceExtension(invoice, pending.decide(PaymentExtension.Status.REJECTED, time.now(), landlordId, null));
            notifications.enqueueTenant(contract, invoice, Event.INVOICE_EXTENSION_REJECTED);
            reconcile(invoice); // Catch up from original deadline; never add the full fee twice.
        }
        invoices.save(invoice);
        return response(invoice);
    }

    /** Third overdue option, independent of whether the tenant has sent a request. */
    @Transactional
    public MonthlyInvoiceResponse extendPaymentDirectly(String invoiceId, String landlordId, PaymentExtensionPayload payload) {
        validateExtension(payload);
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), landlordId);
        requireLandlord(contract, landlordId);
        validateExtensionClock(invoice);
        reconcile(invoice);
        if (!canResolveOverdue(contract, invoice)) throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED);
        PaymentExtension pending = pendingExtension(invoice).orElse(null);
        if (pending != null) {
            if (payload.days() != pending.days()) throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED,
                    "Hãy duyệt hoặc từ chối yêu cầu đang chờ trước khi chọn số ngày khác.");
            approveExtension(invoice, pending, landlordId);
        } else {
            PaymentExtension direct = new PaymentExtension(UUID.randomUUID().toString(), payload.days(), payload.note().trim(),
                    PaymentExtension.Status.PENDING, time.now(), time.now(), landlordId, invoice.getDueAt(), fee(invoice), null, null, null);
            List<PaymentExtension> history = new ArrayList<>(extensions(invoice));
            history.add(direct); invoice.setExtensionRequestsSnapshot(write(history));
            approveExtension(invoice, direct, landlordId);
        }
        invoices.save(invoice);
        return response(invoice);
    }

    private void approveExtension(MonthlyInvoice invoice, PaymentExtension request, String landlordId) {
        Instant approvedAt = time.now();
        Instant currentDue = invoice.getDueAt();
        if (currentDue == null) throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED,
                "Chưa xác định được hạn thanh toán hiện tại; cần đối soát hóa đơn trước khi gia hạn.");
        // Early approval adds time to the existing deadline; overdue approval starts a fresh window now.
        Instant extensionBase = approvedAt.isAfter(currentDue) ? approvedAt : currentDue;
        Instant newDue = extensionBase.plus(Duration.ofDays(request.days()));
        if (!newDue.isAfter(currentDue)) throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED,
                "Hạn gia hạn phải sau hạn thanh toán hiện tại.");
        if (invoice.getOriginalDueAt() == null) invoice.setOriginalDueAt(invoice.getDueAt());
        payments.extendMonthlyDueDate(invoice.getId(), newDue, landlordId);
        replaceExtension(invoice, request.approveWithResumedLateFee(approvedAt, landlordId, newDue));
        invoice.setLateFeeExempt(false); // This decision pauses fees only until newDue; legacy waivers remain untouched.
        invoice.setDueAt(newDue); invoice.setStatus(MonthlyInvoiceStatus.UNPAID);
        invoice.setResolutionMode("PAYMENT_EXTENSION");
        invoice.setPaymentReminderLoggedAt(null); invoice.setOverdueActionLoggedAt(null);
        log.info("BILLING_EXTENSION_APPROVED invoice={} newDue={} frozenFee={}", invoice.getId(), newDue, fee(invoice));
        notifyTenant(invoice, Event.INVOICE_EXTENSION_APPROVED);
    }

    private void validateExtension(PaymentExtensionPayload payload) {
        if (payload == null || payload.days() < 1 || payload.days() > 30 || payload.note() == null
                || payload.note().trim().length() < 10 || payload.note().trim().length() > 1000)
            throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_INVALID);
    }
    private boolean extensionClockValid(MonthlyInvoice invoice) {
        return invoice.getIssuedAt()==null || !time.now().isBefore(invoice.getIssuedAt());
    }

    @Transactional
    public MonthlyInvoiceResponse issueUnilateralTermination(String invoiceId, String landlordId,
            UnilateralTerminationPayloads.Issue payload) {
        validateTerminationReason(payload == null ? null : payload.reason());
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), landlordId);
        requireLandlord(contract, landlordId);
        validateExtensionClock(invoice);
        reconcile(invoice);
        if (contract.getStatus() != ContractStatus.ACTIVE || !canResolveOverdue(contract, invoice)
                || amendments != null && amendments.hasPending(contract))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        List<UnilateralTerminationNotice> history = unilateralHistory(contract);
        if (history.size() >= 100) throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        rejectPendingForOtherDecision(invoice, landlordId);
        var notice = UnilateralTerminationNotice.builder().id(UUID.randomUUID().toString()).invoiceId(invoiceId)
                .version(1).status(UnilateralTerminationNotice.Status.NOTICE).reason(payload.reason().trim())
                .notifiedAt(time.now()).respondBy(time.now().plus(Duration.ofHours(MonthlyBillingSchedule.TERMINATION_REVIEW_HOURS))).build();
        var updated = new ArrayList<>(history); updated.add(notice);
        contract.setUnilateralTerminationSnapshot(write(updated));
        contract.setTerminationProposalInvoiceId(invoiceId); contract.setTerminationProposedAt(time.now());
        contract.setTerminationAcceptedAt(null); contract.setTerminationDeclinedAt(null);
        contract.setTerminationCancelledAt(null); contract.setTerminationCompletedAt(null); contract.setTerminationForcedAt(null);
        invoice.setResolutionMode("TERMINATION");
        contracts.save(contract);
        terminationEvent(invoice, OverdueAction.Type.UNILATERAL_TERMINATION_NOTICE, landlordId,
                "Chủ nhà thông báo đơn phương chấm dứt: " + notice.reason()
                + ". Người thuê có 24 giờ để khiếu nại xin cộng nợ hoặc dời hạn. Không cần xác nhận đồng ý.");
        terminationNotices.tenant(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_NOTICE_ISSUED);
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse requestTerminationReview(String invoiceId, String noticeId, String tenantId,
            UnilateralTerminationPayloads.Review payload) {
        validateTerminationReason(payload == null ? null : payload.reason());
        if (payload.option() == null || payload.option() == UnilateralTerminationNotice.Option.PAYMENT_EXTENSION
                && (payload.days() == null || payload.days() < 1 || payload.days() > 30)
                || payload.option() == UnilateralTerminationNotice.Option.ROLLOVER && payload.days() != null)
            throw new AppException(ContractErrorCode.TERMINATION_NOTICE_INVALID);
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), tenantId);
        if (!tenantId.equals(contract.getTenantId())) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        reconcile(invoice);
        var notice = requireUnilateral(contract, invoiceId, noticeId, payload.expectedVersion());
        if (!canRequestTerminationReview(contract, invoice, notice)) throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        if (payload.option() == UnilateralTerminationNotice.Option.ROLLOVER && !hasNextUnissuedPeriod(contract, invoice))
            throw new AppException(ContractErrorCode.OVERDUE_DEFERRAL_NOT_ALLOWED);
        replaceUnilateral(contract, notice.toBuilder().version(notice.version() + 1)
                .status(UnilateralTerminationNotice.Status.REVIEW_REQUESTED).requestedOption(payload.option())
                .requestedDays(payload.days()).requestReason(payload.reason().trim()).requestedAt(time.now()).build());
        terminationEvent(invoice, OverdueAction.Type.TERMINATION_REVIEW_REQUESTED, tenantId,
                "Người thuê khiếu nại xin " + (payload.option() == UnilateralTerminationNotice.Option.ROLLOVER ? "cộng nợ kỳ sau" : "dời hạn " + payload.days() + " ngày")
                + ": " + payload.reason().trim() + ". Tạm dừng tự chấm dứt, chờ chủ nhà quyết định.");
        terminationNotices.landlord(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_REVIEW_REQUESTED);
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse decideTerminationReview(String invoiceId, String noticeId, String landlordId,
            UnilateralTerminationPayloads.Decision payload) {
        validateTerminationReason(payload == null ? null : payload.reason());
        if (payload.approve() == null) throw new AppException(ContractErrorCode.TERMINATION_NOTICE_INVALID);
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), landlordId); requireLandlord(contract, landlordId);
        reconcile(invoice);
        var notice = requireUnilateral(contract, invoiceId, noticeId, payload.expectedVersion());
        if (!billable(contract) || notice.status() != UnilateralTerminationNotice.Status.REVIEW_REQUESTED
                || !unsettledAndUnreported(invoiceId)) throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        if (!payload.approve()) {
            finishUnilateral(contract, invoice, notice, landlordId, "Chủ nhà từ chối khiếu nại: " + payload.reason().trim());
        } else {
            if (contract.getStatus() != ContractStatus.ACTIVE || notice.requestedOption() == null)
                throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
            if (notice.requestedOption() == UnilateralTerminationNotice.Option.ROLLOVER && !hasNextUnissuedPeriod(contract, invoice))
                throw new AppException(ContractErrorCode.OVERDUE_DEFERRAL_NOT_ALLOWED);
            replaceUnilateral(contract, notice.toBuilder().version(notice.version() + 1)
                    .status(UnilateralTerminationNotice.Status.REPLACED).decidedAt(time.now()).decisionReason(payload.reason().trim()).build());
            contract.setTerminationCancelledAt(time.now()); contracts.save(contract); invoice.setResolutionMode(null);
            if (notice.requestedOption() == UnilateralTerminationNotice.Option.ROLLOVER) deferToNextPeriod(invoiceId, landlordId);
            else extendPaymentDirectly(invoiceId, landlordId, new PaymentExtensionPayload(notice.requestedDays(), notice.requestReason()));
            terminationEvent(invoice, OverdueAction.Type.TERMINATION_REVIEW_DECIDED, landlordId,
                    "Chủ nhà đồng ý khiếu nại, rút quyết định đơn phương và áp dụng " + notice.requestedOption() + ": " + payload.reason().trim());
            terminationNotices.tenant(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_REVIEW_APPROVED);
        }
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse withdrawUnilateralTermination(String invoiceId, String noticeId, String landlordId,
            UnilateralTerminationPayloads.Version payload) {
        if (payload == null) throw new AppException(ContractErrorCode.TERMINATION_NOTICE_INVALID);
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), landlordId); requireLandlord(contract, landlordId);
        reconcile(invoice);
        var notice = requireUnilateral(contract, invoiceId, noticeId, payload.expectedVersion());
        if (!notice.pending() || !billable(contract)) throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        replaceUnilateral(contract, notice.toBuilder().version(notice.version() + 1).status(UnilateralTerminationNotice.Status.WITHDRAWN)
                .decidedAt(time.now()).decisionReason("Chủ nhà rút quyết định đơn phương").build());
        contract.setTerminationCancelledAt(time.now()); contracts.save(contract); invoice.setResolutionMode(null);
        terminationEvent(invoice, OverdueAction.Type.TERMINATION_REVIEW_DECIDED, landlordId, "Chủ nhà rút quyết định đơn phương chấm dứt.");
        terminationNotices.tenant(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_NOTICE_CANCELLED);
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse completeUnilateralHandover(String invoiceId, String noticeId, String landlordId,
            UnilateralTerminationPayloads.Handover payload) {
        if (payload == null || !payload.vacantPossessionConfirmed() || !payload.keysAndAssetsReturned())
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), landlordId); requireLandlord(contract, landlordId);
        var notice = requireUnilateral(contract, invoiceId, noticeId, payload.expectedVersion());
        if (contract.getStatus() != ContractStatus.TERMINATED || notice.status() != UnilateralTerminationNotice.Status.ENDED || notice.handoverAt() != null)
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        DepositRecord deposit = deposits.findByRentalRequestIdForUpdate(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY));
        if (!depositMatchesCurrentTerms(contract, deposit)) throw new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY);
        finalizeTermination(contract, deposit, landlordId, "Đơn phương chấm dứt theo quy tắc HomeSpace; chủ nhà đã nhận lại phòng và tài sản", true);
        replaceUnilateral(contract, notice.toBuilder().version(notice.version() + 1).handoverAt(time.now()).build());
        terminationEvent(invoice, OverdueAction.Type.TERMINATION_HANDOVER, landlordId, "Chủ nhà đã nhận lại phòng/chìa khóa/tài sản; hoàn tất xử lý cọc và mở lại tin đăng.");
        terminationNotices.tenant(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.LEASE_RETURN_COMPLETED);
        return response(invoice);
    }

    private void validateTerminationReason(String reason) {
        if (reason == null || reason.trim().length() < 10 || reason.trim().length() > 1000)
            throw new AppException(ContractErrorCode.TERMINATION_NOTICE_INVALID);
    }
    private List<UnilateralTerminationNotice> unilateralHistory(Contract contract) {
        return contract == null ? List.of() : readList(contract.getUnilateralTerminationSnapshot(), new TypeReference<List<UnilateralTerminationNotice>>() {});
    }
    private UnilateralTerminationNotice unilateralFor(Contract contract, String invoiceId) {
        return unilateralHistory(contract).stream().filter(n -> invoiceId.equals(n.invoiceId())).reduce((a,b) -> b).orElse(null);
    }
    private UnilateralTerminationNotice requireUnilateral(Contract contract, String invoiceId, String noticeId, int version) {
        var notice = unilateralFor(contract, invoiceId);
        if (notice == null || !notice.id().equals(noticeId) || notice.version() != version)
            throw new AppException(ContractErrorCode.TERMINATION_NOTICE_STALE);
        if (time.now().isBefore(notice.notifiedAt()) || notice.requestedAt() != null && time.now().isBefore(notice.requestedAt())
                || notice.decidedAt() != null && time.now().isBefore(notice.decidedAt()))
            throw new AppException(ContractErrorCode.TERMINATION_NOTICE_STALE, "Đồng hồ đang trước lịch sử quyết định; kiểm tra lại thời gian trước khi thao tác.");
        return notice;
    }
    private void replaceUnilateral(Contract contract, UnilateralTerminationNotice notice) {
        contract.setUnilateralTerminationSnapshot(write(unilateralHistory(contract).stream().map(n -> n.id().equals(notice.id()) ? notice : n).toList()));
        contracts.save(contract);
    }
    private boolean canRequestTerminationReview(Contract contract, MonthlyInvoice invoice, UnilateralTerminationNotice notice) {
        return notice != null && notice.status() == UnilateralTerminationNotice.Status.NOTICE && billable(contract)
                && !time.now().isBefore(notice.notifiedAt()) && time.now().isBefore(notice.respondBy()) && unsettledAndUnreported(invoice.getId());
    }
    private boolean hasNextUnissuedPeriod(Contract contract, MonthlyInvoice invoice) {
        return rentalRequests.findById(contract.getRentalRequestId()).map(r -> leaseMonths(contract,r) != null
                && invoice.getPeriodIndex() + 1 < leaseMonths(contract,r)).orElse(false)
                && invoices.findByContractIdAndPeriodIndex(contract.getId(),invoice.getPeriodIndex()+1)
                    .map(next -> next.getStatus() == MonthlyInvoiceStatus.DRAFT).orElse(true);
    }
    private void terminationEvent(MonthlyInvoice invoice, OverdueAction.Type type, String actor, String note) {
        var history = new ArrayList<>(overdueActions(invoice));
        history.add(new OverdueAction(UUID.randomUUID().toString(), type, note, null, time.now(), actor, null, null));
        invoice.setOverdueActionsSnapshot(write(history)); invoices.save(invoice);
    }
    private void syncUnilateral(Contract contract, MonthlyInvoice invoice, PaymentRequest payment) {
        var notice = unilateralFor(contract, invoice.getId());
        if (notice == null || !notice.pending() || payment == null) return;
        if (payment.getStatus() == PaymentStatus.CONFIRMED || invoice.getStatus() == MonthlyInvoiceStatus.PAID) {
            replaceUnilateral(contract, notice.toBuilder().version(notice.version()+1).status(UnilateralTerminationNotice.Status.CANCELLED_PAID)
                    .decidedAt(time.now()).decisionReason("Đã xác nhận thanh toán hóa đơn").build());
            contract.setTerminationCancelledAt(time.now()); contracts.save(contract); invoice.setResolutionMode(null);
            terminationEvent(invoice, OverdueAction.Type.TERMINATION_REVIEW_DECIDED, "SYSTEM", "Rút quyết định đơn phương vì hóa đơn đã được xác nhận thanh toán.");
            terminationNotices.tenant(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_NOTICE_CANCELLED);
        } else if (notice.status() == UnilateralTerminationNotice.Status.NOTICE && !time.now().isBefore(notice.respondBy())
                && billable(contract) && unsettledAndUnreported(invoice.getId())) {
            finishUnilateral(contract, invoice, notice, "SYSTEM", "Hết 24 giờ, người thuê không gửi khiếu nại xin đổi phương án.");
        }
    }
    private void finishUnilateral(Contract contract, MonthlyInvoice invoice, UnilateralTerminationNotice notice, String actor, String reason) {
        replaceUnilateral(contract, notice.toBuilder().version(notice.version()+1).status(UnilateralTerminationNotice.Status.ENDED)
                .decidedAt(time.now()).decisionReason(reason).endedAt(time.now()).build());
        contract.setStatus(ContractStatus.TERMINATED); contract.setTerminationCompletedAt(time.now()); contract.setTerminationForcedAt(time.now());
        contracts.save(contract);
        for (var draft : invoices.findByContractIdOrderByPeriodIndexDesc(contract.getId()))
            if (draft.getStatus() == MonthlyInvoiceStatus.DRAFT) { draft.setStatus(MonthlyInvoiceStatus.CANCELLED); invoices.save(draft); }
        terminationEvent(invoice, OverdueAction.Type.TERMINATION_ENDED, actor, reason + " Hợp đồng đã chấm dứt; công nợ còn nguyên, chờ xác nhận bàn giao.");
        terminationNotices.both(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_ENDED);
        if (lifecycle != null) lifecycle.cancelPendingRenewalsForClosedParent(contract);
    }
    private void requireLegacyTermination(Contract contract, String invoiceId) {
        var notice = unilateralFor(contract, invoiceId);
        if (notice != null && (notice.pending() || notice.status() == UnilateralTerminationNotice.Status.ENDED))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED, "Quyết định đơn phương không dùng thao tác đồng ý/từ chối của luồng cũ.");
    }
    private void validateExtensionClock(MonthlyInvoice invoice) {
        if(!extensionClockValid(invoice))throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED,
                "Thời điểm hiện tại đang trước lúc phát hành hóa đơn; vui lòng kiểm tra lại thời gian.");
    }

    private List<PaymentExtension> extensions(MonthlyInvoice invoice) {
        return readList(invoice.getExtensionRequestsSnapshot(), new TypeReference<List<PaymentExtension>>() {});
    }
    private Optional<PaymentExtension> pendingExtension(MonthlyInvoice invoice) {
        return extensions(invoice).stream().filter(r -> r.status() == PaymentExtension.Status.PENDING).findFirst();
    }
    private void replaceExtension(MonthlyInvoice invoice, PaymentExtension changed) {
        invoice.setExtensionRequestsSnapshot(write(extensions(invoice).stream()
                .map(r -> r.id().equals(changed.id()) ? changed : r).toList()));
    }
    private BigDecimal fee(MonthlyInvoice invoice) { return invoice.getLateFeeAmount() == null ? BigDecimal.ZERO : invoice.getLateFeeAmount(); }
    private boolean extensionActive(MonthlyInvoice invoice) {
        return "PAYMENT_EXTENSION".equals(invoice.getResolutionMode()) && invoice.getDueAt() != null
                && !time.now().isAfter(invoice.getDueAt());
    }
    private boolean canRequestExtension(Contract contract, MonthlyInvoice invoice) {
        return billable(contract) && invoice.getDueAt() != null && extensionClockValid(invoice)
                && List.of(MonthlyInvoiceStatus.UNPAID, MonthlyInvoiceStatus.OVERDUE).contains(invoice.getStatus())
                && invoice.getDeferredAt() == null && !activeTerminationProposal(contract) && !extensionActive(invoice)
                && unsettledAndUnreported(invoice.getId()) && pendingExtension(invoice).isEmpty()
                && extensions(invoice).size() < 100
                && extensions(invoice).stream().noneMatch(r -> Objects.equals(r.baseDueAt(), invoice.getDueAt()));
    }
    private boolean canResolveOverdue(Contract contract, MonthlyInvoice invoice) {
        return billable(contract) && extensionClockValid(invoice)
                && invoice.getStatus() == MonthlyInvoiceStatus.OVERDUE && invoice.getDueAt() != null
                && !time.now().isBefore(actionRequiredAt(invoice)) && invoice.getDeferredAt() == null
                && !activeTerminationProposal(contract) && !extensionActive(invoice) && unsettledAndUnreported(invoice.getId());
    }
    private void requireLandlord(Contract contract, String actorId) {
        if (!actorId.equals(contract.getLandlordId())) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
    }
    private void rejectPendingForOtherDecision(MonthlyInvoice invoice, String actorId) {
        pendingExtension(invoice).ifPresent(r -> replaceExtension(invoice,
                r.decide(PaymentExtension.Status.REJECTED, time.now(), actorId, null)));
        reconcile(invoice);
    }
    // The contract row is already locked and managed in this transaction; the writer re-validates parties.
    private Contract lockedContract(MonthlyInvoice invoice) {
        return contracts.findById(invoice.getContractId()).orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
    }
    private void notifyTenant(MonthlyInvoice invoice, Event type) { notifications.enqueueTenant(lockedContract(invoice), invoice, type); }
    private void notifyLandlord(MonthlyInvoice invoice, Event type) { notifications.enqueueLandlord(lockedContract(invoice), invoice, type); }
    private void notifyBoth(MonthlyInvoice invoice, Event type) { notifications.enqueueBoth(lockedContract(invoice), invoice, type); }

    /** Lock order shared with scheduler: contract -> invoice -> payment. */
    private MonthlyInvoice lockInvoice(String invoiceId) {
        String contractId = invoices.findContractIdByInvoiceId(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
        contracts.findByIdForUpdate(contractId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        MonthlyInvoice locked = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
        payments.lockMonthlyPayment(invoiceId);
        return locked;
    }

    /** Restart-safe: unique (contract, period) plus a contract row lock on manual and scheduled sync. */
    @Transactional
    public List<MonthlyInvoiceResponse> list(String contractId, String actorId) {
        Contract contract = contracts.findByIdForUpdate(contractId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (com.hs.contract.deposit.DepositAgreementService.isDeposit(contract))
            throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED, "Hồ sơ cọc không có hóa đơn thuê hàng tháng.");
        if (!actorId.equals(contract.getLandlordId()) && !actorId.equals(contract.getTenantId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (billable(contract)) syncContract(contractId);
        else if (contract.getStatus() == ContractStatus.TERMINATED)
            invoices.findByContractIdOrderByPeriodIndexDesc(contractId).forEach(i -> reconcile(lockInvoice(i.getId())));
        var result=new ArrayList<>(invoices.findByContractIdOrderByPeriodIndexDesc(contractId).stream()
                .filter(invoice -> actorId.equals(contract.getLandlordId())
                        || invoice.getStatus() != MonthlyInvoiceStatus.DRAFT)
                .map(this::response).toList());
        if(contract.getParentContractId()==null) {
            payments.attachOnboardingToContract(contract.getRentalRequestId(),contractId);
            payments.findByRentalRequestIdAndType(contract.getRentalRequestId(),PaymentType.INITIAL).ifPresent(first -> {
                var rental=rentalRequests.findById(contract.getRentalRequestId()).orElseThrow();
                result.add(FirstRentInvoice.from(contractId,rental,payments.getPaymentRequest(first.getId(),actorId),time.now()));
            });
        }
        return result;
    }

    /** Record an escalation after five overdue days; this never changes the lease or payment terms. */
    @Transactional
    public MonthlyInvoiceResponse recordOverdueAction(String invoiceId, String landlordId,
                                                       CreateOverdueActionRequest request) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), landlordId);
        if (!landlordId.equals(contract.getLandlordId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (contract.getStatus() != ContractStatus.ACTIVE || invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE
                || invoice.getDueAt() == null || time.now().isBefore(actionRequiredAt(invoice))
                || payments.findByInvoiceId(invoiceId).map(p -> p.getStatus() == PaymentStatus.TRANSFER_REPORTED
                    || p.getStatus() == PaymentStatus.DISPUTED || p.getStatus() == PaymentStatus.CONFIRMED)
                    .orElse(true))
            throw new AppException(ContractErrorCode.OVERDUE_ACTION_NOT_ALLOWED);
        if (request == null || request.type() == null || request.note() == null
                || !List.of(OverdueAction.Type.PAYMENT_REQUEST, OverdueAction.Type.EXTENSION_PROPOSAL,
                    OverdueAction.Type.MUTUAL_TERMINATION_PROPOSAL, OverdueAction.Type.LEGAL_REVIEW).contains(request.type())
                || request.note().trim().length() < 10 || request.note().trim().length() > 1000
                || request.type() == OverdueAction.Type.EXTENSION_PROPOSAL && request.proposedDate() == null
                || request.proposedDate() != null && request.type() != OverdueAction.Type.EXTENSION_PROPOSAL
                    && request.type() != OverdueAction.Type.MUTUAL_TERMINATION_PROPOSAL
                || request.proposedDate() != null && !request.proposedDate().isAfter(time.today()))
            throw new AppException(ContractErrorCode.OVERDUE_ACTION_INVALID);
        List<OverdueAction> history = new ArrayList<>(overdueActions(invoice));
        if (request.type() == OverdueAction.Type.EXTENSION_PROPOSAL
                && request.proposedDate().isAfter(time.today().plusDays(30)))
            throw new AppException(ContractErrorCode.OVERDUE_ACTION_INVALID);
        if (history.size() >= 100) throw new AppException(ContractErrorCode.OVERDUE_ACTION_INVALID);
        OverdueAction action = new OverdueAction(UUID.randomUUID().toString(), request.type(),
                request.note().trim(), request.proposedDate(), time.now(), landlordId, null, null);
        history.add(action);
        invoice.setOverdueActionsSnapshot(write(history));
        log.info("BILLING_OVERDUE_ACTION_RECORDED contract={} invoice={} action={} type={}",
                contract.getId(), invoiceId, action.id(), action.type());
        invoices.save(invoice);
        notifications.enqueueTenant(contract, invoice, Event.INVOICE_OVERDUE_ACTION_RECORDED);
        return response(invoice);
    }

    /** Tenant acknowledgement is evidence of viewing, not acceptance of a new signed agreement. */
    @Transactional
    public MonthlyInvoiceResponse acknowledgeOverdueAction(String invoiceId, String actionId,
                                                             String tenantId, AcknowledgeOverdueActionRequest request) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), tenantId);
        if (!tenantId.equals(contract.getTenantId())) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (request == null || request.note() == null || request.note().trim().length() > 1000)
            throw new AppException(ContractErrorCode.OVERDUE_ACTION_INVALID);
        List<OverdueAction> history = new ArrayList<>(overdueActions(invoice));
        int index = -1;
        for (int i = 0; i < history.size(); i++) if (history.get(i).id().equals(actionId)) { index = i; break; }
        if (index < 0) throw new AppException(ContractErrorCode.OVERDUE_ACTION_NOT_FOUND);
        OverdueAction action = history.get(index);
        if (action.acknowledgedAt() != null) throw new AppException(ContractErrorCode.OVERDUE_ACTION_NOT_ALLOWED);
        history.set(index, new OverdueAction(action.id(), action.type(), action.note(), action.proposedDate(),
                action.createdAt(), action.createdBy(), request.note().trim(), time.now()));
        invoice.setOverdueActionsSnapshot(write(history));
        log.info("BILLING_OVERDUE_ACTION_ACKNOWLEDGED contract={} invoice={} action={}",
                contract.getId(), invoiceId, actionId);
        invoices.save(invoice);
        // Termination/system entries share this list; their notices belong to Phase 6.
        if (RECORDED_BY_LANDLORD.contains(action.type()))
            notifications.enqueueLandlord(contract, invoice, Event.INVOICE_OVERDUE_ACTION_ACKNOWLEDGED);
        return response(invoice);
    }

    /** Freeze the currently accrued balance and carry it exactly once when the next bill is issued. */
    @Transactional
    public MonthlyInvoiceResponse deferToNextPeriod(String invoiceId, String landlordId) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), landlordId);
        if (!landlordId.equals(contract.getLandlordId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        RentalRequest rental = rentalRequests.findById(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
        reconcile(invoice);
        if (contract.getStatus() != ContractStatus.ACTIVE || activeTerminationProposal(contract) || extensionActive(invoice)
                || leaseMonths(contract, rental) == null
                || invoice.getPeriodIndex() + 1 >= leaseMonths(contract, rental)
                || invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE || invoice.getDueAt() == null
                || time.now().isBefore(actionRequiredAt(invoice)))
            throw new AppException(ContractErrorCode.OVERDUE_DEFERRAL_NOT_ALLOWED);
        if (invoices.findByContractIdAndPeriodIndex(contract.getId(), invoice.getPeriodIndex() + 1)
                .filter(next -> next.getStatus() != MonthlyInvoiceStatus.DRAFT).isPresent())
            throw new AppException(ContractErrorCode.OVERDUE_DEFERRAL_NOT_ALLOWED);
        if (invoice.getDeferredAt() != null) return response(invoice);
        reconcile(invoice);
        PaymentStatus paymentStatus = payments.findByInvoiceId(invoiceId)
                .map(PaymentRequest::getStatus).orElse(null);
        if (invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE || paymentStatus == null
                || !List.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE,
                    PaymentStatus.REJECTED).contains(paymentStatus))
            throw new AppException(ContractErrorCode.OVERDUE_DEFERRAL_NOT_ALLOWED);
        rejectPendingForOtherDecision(invoice, landlordId);
        invoice.setDeferredAt(time.now());
        invoice.setResolutionMode("ROLLOVER");
        log.warn("BILLING_DEFERRED landlord={} tenant={} contract={} invoice={} amount={}",
                landlordId, contract.getTenantId(), contract.getId(), invoiceId, invoice.getTotalAmount());
        invoices.save(invoice);
        notifications.enqueueTenant(contract, invoice, Event.INVOICE_DEFERRED);
        return response(invoice);
    }

    /** Proposal alone has no legal or inventory effect. */
    @Transactional
    public MonthlyInvoiceResponse proposeMutualTermination(String invoiceId, String landlordId) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findByIdForUpdate(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!landlordId.equals(contract.getLandlordId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        reconcile(invoice);
        if (extensionActive(invoice) || contract.getStatus() != ContractStatus.ACTIVE || invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE
                || invoice.getDueAt() == null
                || invoice.getDeferredAt() != null || time.now().isBefore(actionRequiredAt(invoice))
                || activeTerminationProposal(contract))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        PaymentStatus status = payments.findByInvoiceId(invoiceId).map(PaymentRequest::getStatus).orElse(null);
        if (status == null || !List.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE,
                PaymentStatus.REJECTED).contains(status))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        DepositRecord deposit = deposits.findByRentalRequestId(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY));
        if (!depositMatchesCurrentTerms(contract,deposit))
            throw new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY);
        rejectPendingForOtherDecision(invoice, landlordId);
        if (amendments != null && amendments.hasPending(contract))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED, "Hãy hoàn tất hoặc rút đề nghị phụ lục trước khi đề nghị chấm dứt.");
        invoice.setResolutionMode("TERMINATION"); invoices.save(invoice);
        contract.setTerminationProposalInvoiceId(invoiceId);
        contract.setTerminationProposedAt(time.now());
        contract.setTerminationAcceptedAt(null);
        contract.setTerminationDeclinedAt(null);
        contract.setTerminationCancelledAt(null);
        contracts.save(contract);
        log.warn("BILLING_TERMINATION_PROPOSED landlord={} tenant={} contract={} invoice={}",
                landlordId, contract.getTenantId(), contract.getId(), invoiceId);
        terminationNotices.tenant(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_RESPONSE_NEEDED);
        return response(invoice);
    }

    /** Explicit tenant agreement includes full retention of the deposit after handover. */
    @Transactional
    public MonthlyInvoiceResponse acceptMutualTermination(String invoiceId, String tenantId,
                                                           AcceptTerminationRequest request) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findByIdForUpdate(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!tenantId.equals(contract.getTenantId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        requireLegacyTermination(contract, invoiceId);
        if (request == null || !request.acceptEarlyTermination() || !request.acceptDepositRetention()
                || !request.acknowledgeOutstandingDebt())
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        if (contract.getStatus() != ContractStatus.ACTIVE
                || !invoiceId.equals(contract.getTerminationProposalInvoiceId())
                || contract.getTerminationAcceptedAt() != null
                || contract.getTerminationDeclinedAt() != null
                || contract.getTerminationCancelledAt() != null
                || invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE
                || !unsettledAndUnreported(invoiceId))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        reconcile(invoice);
        if (invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE || !unsettledAndUnreported(invoiceId))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        contract.setTerminationAcceptedAt(time.now());
        contracts.save(contract);
        log.warn("BILLING_TERMINATION_ACCEPTED landlord={} tenant={} contract={} invoice={}",
                contract.getLandlordId(), tenantId, contract.getId(), invoiceId);
        terminationNotices.landlord(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_AGREED);
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse declineMutualTermination(String invoiceId, String tenantId) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findByIdForUpdate(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!tenantId.equals(contract.getTenantId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        requireLegacyTermination(contract, invoiceId);
        if (contract.getStatus() != ContractStatus.ACTIVE
                || !invoiceId.equals(contract.getTerminationProposalInvoiceId())
                || contract.getTerminationAcceptedAt() != null || contract.getTerminationDeclinedAt() != null
                || contract.getTerminationCancelledAt() != null)
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        contract.setTerminationDeclinedAt(time.now());
        invoice.setResolutionMode(null); invoices.save(invoice);
        contracts.save(contract);
        log.warn("BILLING_TERMINATION_DECLINED tenant={} contract={} invoice={}",
                tenantId, contract.getId(), invoiceId);
        terminationNotices.landlord(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_DECLINED);
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse withdrawMutualTermination(String invoiceId, String landlordId) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findByIdForUpdate(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!landlordId.equals(contract.getLandlordId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        requireLegacyTermination(contract, invoiceId);
        if (contract.getStatus() != ContractStatus.ACTIVE
                || !invoiceId.equals(contract.getTerminationProposalInvoiceId())
                || contract.getTerminationProposedAt() == null
                || contract.getTerminationCancelledAt() != null
                || contract.getTerminationCompletedAt() != null
                || contract.getTerminationAcceptedAt() != null)
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        contract.setTerminationCancelledAt(time.now());
        invoice.setResolutionMode(null); invoices.save(invoice);
        contracts.save(contract);
        log.warn("BILLING_TERMINATION_WITHDRAWN landlord={} contract={} invoice={}",
                landlordId, contract.getId(), invoiceId);
        terminationNotices.tenant(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_WITHDRAWN);
        return response(invoice);
    }

    /** Complete only after bilateral agreement and actual return of the property. */
    @Transactional
    public MonthlyInvoiceResponse completeMutualTermination(String invoiceId, String landlordId,
                                                             CompleteTerminationRequest request) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findByIdForUpdate(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!landlordId.equals(contract.getLandlordId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        requireLegacyTermination(contract, invoiceId);
        if (request == null || !request.vacantPossessionConfirmed() || !request.keysAndAssetsReturned())
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        if (contract.getStatus() != ContractStatus.ACTIVE
                || !invoiceId.equals(contract.getTerminationProposalInvoiceId())
                || contract.getTerminationAcceptedAt() == null
                || contract.getTerminationDeclinedAt() != null
                || contract.getTerminationCancelledAt() != null
                || invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE
                || !unsettledAndUnreported(invoiceId))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        DepositRecord deposit = deposits.findByRentalRequestIdForUpdate(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY));
        if (!depositMatchesCurrentTerms(contract,deposit))
            throw new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY);
        finalizeTermination(contract, deposit, landlordId,
                "Hai bên đồng ý chấm dứt sớm, đã bàn giao; cọc thuộc chủ nhà", false);
        log.warn("BILLING_CONTRACT_TERMINATED landlord={} tenant={} contract={} invoice={} depositRetained={}",
                landlordId, contract.getTenantId(), contract.getId(), invoiceId, deposit.getOriginalAmount());
        return response(invoice);
    }

    /** Owner-initiated branch only for an explicitly signed five-day clause and after a declined proposal. */
    @Transactional
    public MonthlyInvoiceResponse forceTerminationAfterDecline(String invoiceId, String landlordId,
                                                               ForceTerminationRequest request) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findByIdForUpdate(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!landlordId.equals(contract.getLandlordId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        requireLegacyTermination(contract, invoiceId);
        if (request == null || !request.signedClauseAcknowledged() || !request.tenantNotified()
                || !request.vacantPossessionConfirmed() || !request.keysAndAssetsReturned()
                || contract.getStatus() != ContractStatus.ACTIVE || contract.getSignedAt() == null
                || !invoiceId.equals(contract.getTerminationProposalInvoiceId())
                || contract.getTerminationDeclinedAt() == null || contract.getTerminationCancelledAt() != null
                || contract.getTerminationAcceptedAt() != null || contract.getTerminationCompletedAt() != null
                || invoice.getDeferredAt() != null || invoice.getDueAt() == null
                || extensionActive(invoice) || pendingExtension(invoice).isPresent()
                || time.now().isBefore(actionRequiredAt(invoice)) || !signedLandlordTerminationClause(contract))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        reconcile(invoice);
        if (invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE || !unsettledAndUnreported(invoiceId))
            throw new AppException(ContractErrorCode.TERMINATION_NOT_ALLOWED);
        DepositRecord deposit = deposits.findByRentalRequestIdForUpdate(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY));
        if (!depositMatchesCurrentTerms(contract,deposit))
            throw new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY);
        finalizeTermination(contract, deposit, landlordId,
                "Chủ nhà chấm dứt theo điều khoản quá hạn trong hợp đồng đã ký sau khi người thuê từ chối đề nghị; đã nhận lại phòng", true);
        log.warn("BILLING_CONTRACT_FORCED_TERMINATION landlord={} tenant={} contract={} invoice={} "
                        + "proposalDeclinedAt={} signedClause=true tenantNotified=true vacantPossession=true depositRetained={}",
                landlordId, contract.getTenantId(), contract.getId(), invoiceId,
                contract.getTerminationDeclinedAt(), deposit.getOriginalAmount());
        return response(invoice);
    }

    private boolean depositMatchesCurrentTerms(Contract contract,DepositRecord deposit) {
        if(deposit.getStatus()!=DepositStatus.HELD || deposit.getHeldAmount()==null)return false;
        var revision=terms==null?revisions.findById(contract.getCurrentRevisionId()).orElse(null):terms.at(contract,time.today());
        var financial=revision==null?Map.<String,Object>of():read(revision.getFinancialSnapshot(),new TypeReference<Map<String,Object>>(){});
        BigDecimal target=financial==null?null:decimal(financial.get("depositAmountValue"));
        if(target==null)target=deposit.getOriginalAmount();
        return target!=null && deposit.getHeldAmount().compareTo(target)==0;
    }

    private void finalizeTermination(Contract contract, DepositRecord deposit,
                                     String landlordId, String reason, boolean forced) {
        boolean ending = contract.getStatus() == ContractStatus.ACTIVE;
        if(payments.getContractPaymentRequests(contract.getId(),contract.getLandlordId()).stream().anyMatch(p->p.type()==PaymentType.DEPOSIT_ADJUSTMENT && p.status()!=PaymentStatus.CONFIRMED))
            throw new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY);
        listingStatusService.releaseRentedAfterTermination(contract.getListingId(), landlordId);
        parkingReservations.releaseReservationsForContract(contract.getId());
        if(deposit.getHeldAmount().signum()>0) {
            deposit.setRetainedAmount(deposit.getHeldAmount());
            deposit.setStatus(DepositStatus.RETAINED_BY_LANDLORD);
            deposit.setRetainedAt(time.now());
            deposit.setRetainedReason(reason);
        }else {
            if(deposit.getRetainedAmount()==null)deposit.setRetainedAmount(BigDecimal.ZERO);
            deposit.setStatus(deposit.getRetainedAmount().signum()>0?DepositStatus.RETAINED_BY_LANDLORD:DepositStatus.HELD);
        }
        deposit.setHeldAmount(BigDecimal.ZERO);
        deposit.setRefundableAmount(BigDecimal.ZERO);
        deposits.save(deposit);
        contract.setStatus(ContractStatus.TERMINATED);
        if (contract.getTerminationCompletedAt() == null) contract.setTerminationCompletedAt(time.now());
        if (forced && contract.getTerminationForcedAt() == null) contract.setTerminationForcedAt(time.now());
        contracts.save(contract);
        if(lifecycle!=null) lifecycle.cancelPendingRenewalsForClosedParent(contract);
        if (ending) terminationNotices.both(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_ENDED);
    }

    private boolean signedLandlordTerminationClause(Contract contract) {
        if (contract.getCurrentRevisionId() == null) return false;
        return revisions.findById(contract.getCurrentRevisionId()).map(revision -> {
            Map<String, Object> policy = read(revision.getPoliciesSnapshot(),
                    new TypeReference<Map<String, Object>>() {});
            return policy != null && Boolean.TRUE.equals(policy.get("overdueLandlordTerminationAfterFiveDays"))
                    && revision.getSpecialTerms() != null
                    && revision.getSpecialTerms().contains("bên cho thuê vẫn có thể thực hiện quyền chấm dứt đã thỏa thuận");
        }).orElse(false);
    }

    /** Landlord records readings and optional charges before the 10:00 automatic issue cutoff. */
    @Transactional
    public MonthlyInvoiceResponse prepare(String invoiceId, String landlordId, IssueMonthlyInvoiceRequest request) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findById(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!contract.getLandlordId().equals(landlordId)) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (!billable(contract)) throw new AppException(ContractErrorCode.INVOICE_NOT_ACTIVE);
        if (invoice.getStatus() != MonthlyInvoiceStatus.DRAFT
                || time.today().isBefore(invoice.getPeriodEndExclusive().minusDays(1))
                || !time.now().isBefore(meterDeadline(invoice)))
            throw new AppException(ContractErrorCode.INVOICE_NOT_READY);
        ContractRevision revision = costRevision(contract, invoice);
        RentalRequest rental = rentalRequests.findById(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
        List<Map<String, Object>> charges = readList(revision.getChargesSnapshot(),
                new TypeReference<List<Map<String, Object>>>() {});
        if (meterRate(charges, rental, "PER_KWH") != null) {
            validateReading(meterStart(contract, invoice, "electricityInitial", true), request.electricityEnd());
            invoice.setElectricityEnd(request.electricityEnd());
        }
        if (meterRate(charges, rental, "PER_M3") != null) {
            validateReading(meterStart(contract, invoice, "waterInitial", false), request.waterEnd());
            invoice.setWaterEnd(request.waterEnd());
        }
        validateExtras(request.extraCharges());
        invoice.setDraftExtraChargesSnapshot(write(request.extraCharges() == null ? List.of() : request.extraCharges()));
        log.info("BILLING_METER_PREPARED contract={} invoice={} period={}", contract.getId(), invoice.getId(), invoice.getPeriodIndex());
        return response(invoices.save(invoice));
    }

    @Transactional
    public MonthlyInvoiceResponse issue(String invoiceId, String landlordId, IssueMonthlyInvoiceRequest request) {
        return issue(invoiceId, landlordId, request, false);
    }

    private MonthlyInvoiceResponse issue(String invoiceId, String landlordId,
                                         IssueMonthlyInvoiceRequest request, boolean automatic) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findById(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!contract.getLandlordId().equals(landlordId)) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (!billable(contract)) throw new AppException(ContractErrorCode.INVOICE_NOT_ACTIVE);
        // The landlord may close the meters and publish on the final day of the period.
        if (invoice.getStatus() != MonthlyInvoiceStatus.DRAFT
                || time.today().isBefore(invoice.getPeriodEndExclusive().minusDays(1)))
            throw new AppException(ContractErrorCode.INVOICE_NOT_READY);
        if (invoice.getPeriodIndex() > 0) {
            MonthlyInvoice previous = invoices.findByContractIdAndPeriodIndex(
                    contract.getId(), invoice.getPeriodIndex() - 1)
                    .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_READY));
            if (previous.getStatus() == MonthlyInvoiceStatus.DRAFT)
                throw new AppException(ContractErrorCode.INVOICE_NOT_READY);
        }
        RentalRequest rental = rentalRequests.findById(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
        ContractRevision revision = costRevision(contract, invoice);
        Map<String, Object> financial = read(revision.getFinancialSnapshot(),
                new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> signedCharges = readList(revision.getChargesSnapshot(),
                new TypeReference<List<Map<String, Object>>>() {});
        if (financial == null) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        invoice.setRentPeriodStart(null);invoice.setRentPeriodEndExclusive(null);
        if (signedCharges.stream().anyMatch(c -> "STATE_WATER_RATE".equals(c.get("billingMethod"))
                && !Boolean.TRUE.equals(c.get("includedInRent"))))
            throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        List<InvoiceLine> lines = new ArrayList<>();

        // Existing contracts may have prepaid fixed fees; new contracts only prepay rent.
        boolean firstPeriodFeesPrepaid = invoice.getPeriodIndex() == 0
                && verifyInitialPeriodSettled(contract, financial, signedCharges);

        // The first lease period was paid upfront. At the end of period N, collect
        // its actual expenses and the rent for period N+1, if the lease continues.
        if (leaseMonths(contract, rental) == null || leaseMonths(contract, rental) <= invoice.getPeriodIndex()
                || rental.getMoveInDate() == null)
            throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        if (invoice.getPeriodIndex() + 1 < leaseMonths(contract, rental)) {
            ContractRevision rentRevision = terms == null ? revision : terms.at(contract, invoice.getPeriodEndExclusive());
            Map<String, Object> rentFinancial = read(rentRevision.getFinancialSnapshot(), new TypeReference<Map<String, Object>>() {});
            BigDecimal rent = decimal(rentFinancial.get("amountValue"));
            if (rent == null || rent.signum() <= 0) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            if (!String.valueOf(rentFinancial.get("amountNumber")).contains(ContractRenderService.formatVND(rent)))
                throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            invoice.setRentTermsRevisionId(rentRevision.getId());
            LocalDate nextStart = invoice.getPeriodEndExclusive();
            LocalDate coveredThrough=rental.getMoveInDate().plusMonths(1);
            for(var paidPeriod:invoices.findByContractIdOrderByPeriodIndexDesc(contract.getId())) {
                if(paidPeriod.getPeriodIndex()>=invoice.getPeriodIndex() || paidPeriod.getStatus()==MonthlyInvoiceStatus.DRAFT || paidPeriod.isFinalCostSupplement())continue;
                LocalDate covered=paidPeriod.getRentPeriodEndExclusive();
                if(covered==null && readList(paidPeriod.getLineItemsSnapshot(),new TypeReference<List<InvoiceLine>>(){}).stream().anyMatch(l->"RENT".equals(l.type())))
                    covered=rental.getMoveInDate().plusMonths(paidPeriod.getPeriodIndex()+2L);
                if(covered!=null && covered.isAfter(coveredThrough))coveredThrough=covered;
            }
            if(!nextStart.isBefore(coveredThrough)) {
                int cycle=rentFinancial.get("paymentCycleMonths")==null?1:FreeFormAmendmentTerms.integer(rentFinancial.get("paymentCycleMonths"),1,12);
                int months=Math.min(cycle,leaseMonths(contract,rental)-invoice.getPeriodIndex()-1);
                LocalDate nextEnd=rental.getMoveInDate().plusMonths(invoice.getPeriodIndex()+1L+months);
                invoice.setRentPeriodStart(nextStart);invoice.setRentPeriodEndExclusive(nextEnd);
                lines.add(new InvoiceLine("RENT", (months==1?"Tiền phòng tháng "+(invoice.getPeriodIndex()+2):"Tiền phòng " + months + " tháng")
                        + " (" + nextStart.format(BILLING_DATE) + "–" + nextEnd.minusDays(1).format(BILLING_DATE) + ")",
                        BigDecimal.valueOf(months), rent, rent.multiply(BigDecimal.valueOf(months))));
            }
        }
        if (!firstPeriodFeesPrepaid) {
            for (Map<String, Object> charge : signedCharges) {
                String method = String.valueOf(charge.get("billingMethod"));
                if (Set.of("PER_KWH", "PER_M3", "FREE", "INCLUDED", "NOT_APPLICABLE").contains(method)) continue;
                BigDecimal amount = decimal(charge.get("estimatedMonthlyAmount"));
                if (amount != null && amount.signum() < 0)
                    throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
                if (amount != null && amount.signum() > 0 && !Boolean.TRUE.equals(charge.get("includedInRent"))) {
                    String displayed = String.valueOf(charge.get("amountAndMethod"));
                    if (!displayed.contains(ContractRenderService.formatVND(amount))
                            && !displayed.contains(RentalCostCalculator.formatVND(amount)))
                        throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
                    lines.add(new InvoiceLine("SERVICE_FEE", String.valueOf(charge.get("name")),
                            BigDecimal.ONE, amount, amount));
                }
            }
        }

        BigDecimal electricRate = meterRate(signedCharges, rental, "PER_KWH");
        BigDecimal waterRate = meterRate(signedCharges, rental, "PER_M3");
        if (electricRate != null) {
            BigDecimal electricityStart = meterStart(contract, invoice, "electricityInitial", true);
            validateReading(electricityStart, request.electricityEnd());
            BigDecimal quantity = request.electricityEnd().subtract(electricityStart);
            lines.add(new InvoiceLine("ELECTRICITY", "Điện theo công tơ", quantity,
                    electricRate, quantity.multiply(electricRate).setScale(0, RoundingMode.HALF_UP)));
            invoice.setElectricityStart(electricityStart);
            invoice.setElectricityEnd(request.electricityEnd());
        }
        if (waterRate != null) {
            BigDecimal waterStart = meterStart(contract, invoice, "waterInitial", false);
            validateReading(waterStart, request.waterEnd());
            BigDecimal quantity = request.waterEnd().subtract(waterStart);
            lines.add(new InvoiceLine("WATER", "Nước theo đồng hồ", quantity,
                    waterRate, quantity.multiply(waterRate).setScale(0, RoundingMode.HALF_UP)));
            invoice.setWaterStart(waterStart);
            invoice.setWaterEnd(request.waterEnd());
        }
        if (request.extraCharges() != null) {
            validateExtras(request.extraCharges());
            for (IssueMonthlyInvoiceRequest.ExtraCharge extra : request.extraCharges()) {
                lines.add(new InvoiceLine("EXTRA", extra.description().trim(), BigDecimal.ONE,
                        extra.amount(), extra.amount()));
            }
        }
        MonthlyInvoice carryFrom = null;
        if (invoice.getPeriodIndex() > 0) {
            MonthlyInvoice previous = invoices.findByContractIdAndPeriodIndex(
                    contract.getId(), invoice.getPeriodIndex() - 1).orElseThrow();
            reconcile(previous);
            autoDeferIdleInvoice(previous);
            if (previous.getDeferredAt() != null && previous.getStatus() != MonthlyInvoiceStatus.PAID
                    && previous.getStatus() != MonthlyInvoiceStatus.ROLLED_OVER) {
                PaymentStatus oldStatus = payments.findByInvoiceId(previous.getId())
                        .map(PaymentRequest::getStatus).orElse(null);
                if (oldStatus == null || !List.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE,
                        PaymentStatus.REJECTED).contains(oldStatus))
                    throw new AppException(ContractErrorCode.OVERDUE_DEFERRAL_NOT_ALLOWED);
                lines.add(new InvoiceLine("BALANCE_FORWARD", "Công nợ kỳ thanh toán "
                        + (previous.getPeriodIndex() + 2) + " (gồm phí phạt đã chốt)", BigDecimal.ONE,
                        previous.getTotalAmount(), previous.getTotalAmount()));
                carryFrom = previous;
            }
        }
        BigDecimal total = lines.stream().map(InvoiceLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() < 0 || total.scale() > 2 && total.stripTrailingZeros().scale() > 2)
            throw new AppException(ContractErrorCode.INVOICE_AMOUNT_INVALID);
        invoice.setLineItemsSnapshot(write(lines));
        invoice.setIssuedBillingKind(lines.stream().anyMatch(l->"RENT".equals(l.type()))?"RECURRING"
                :invoice.getPeriodIndex()+1<leaseMonths(contract,rental)?"COSTS_ONLY":"FINAL_SETTLEMENT");
        invoice.setCostTermsRevisionId(revision.getId());
        invoice.setTotalAmount(total);
        invoice.setBaseAmount(total);
        invoice.setLateFeeAmount(BigDecimal.ZERO);
        invoice.setIssuedAt(time.now());
        // Preserve the fifth-day term on already-signed contracts; new revisions
        // explicitly anchor the payment window to the end of each lease period.
        Instant scheduledDue;
        if (financial.get("paymentDueOffsetDays") != null) {
            int offset;
            try { offset = Integer.parseInt(String.valueOf(financial.get("paymentDueOffsetDays"))); }
            catch (NumberFormatException ex) { throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE); }
            if (offset < 0 || offset > 30)
                throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            scheduledDue = invoice.getPeriodEndExclusive().plusDays(offset).atTime(23,59,59).atZone(BillingTime.ZONE).toInstant();
        } else {
            LocalDate fifth = invoice.getPeriodEndExclusive().withDayOfMonth(5);
            if (fifth.isBefore(invoice.getPeriodEndExclusive())) fifth = fifth.plusMonths(1);
            scheduledDue = fifth.atTime(23, 59, 59).atZone(BillingTime.ZONE).toInstant();
        }
        // A scheduler catch-up represents missed billing milestones, not a new
        // payment grace period. Keep the signed due date even if it has passed.
        // Manual late publication still gives the payer at least one day.
        invoice.setDueAt(automatic || scheduledDue.isAfter(time.now().plus(Duration.ofDays(1)))
                ? scheduledDue : time.now().plus(Duration.ofDays(1)));
        invoice.setOriginalDueAt(invoice.getDueAt());
        if (carryFrom != null) {
            try { payments.cancelMonthlyForRollover(carryFrom.getId()); }
            catch (IllegalStateException ex) {
                throw new AppException(ContractErrorCode.OVERDUE_DEFERRAL_NOT_ALLOWED);
            }
            carryFrom.setStatus(MonthlyInvoiceStatus.ROLLED_OVER);
            carryFrom.setRolledToInvoiceId(invoice.getId());
            invoices.save(carryFrom);
            // About the OLD invoice (its QR is cancelled); the new invoice gets its own ISSUED below.
            notifications.enqueueTenant(contract, carryFrom, Event.INVOICE_ROLLED_OVER);
            log.info("BILLING_BALANCE_ROLLED contract={} from={} to={} amount={}",
                    contract.getId(), carryFrom.getId(), invoice.getId(), carryFrom.getTotalAmount());
        }
        if (total.signum() == 0) {
            invoice.setStatus(MonthlyInvoiceStatus.PAID);
            invoice.setPaidAt(time.now());
        } else {
            PaymentRequest payment = payments.createMonthlyPayment(invoice.getId(), contract.getId(),
                    contract.getRentalRequestId(), contract.getListingId(), contract.getTenantId(),
                    contract.getLandlordId(), total, invoice.getLineItemsSnapshot(), invoice.getDueAt());
            invoice.setPaymentRequestId(payment.getId());
            invoice.setStatus(MonthlyInvoiceStatus.UNPAID);
            notifications.enqueueTenant(contract, invoice, Event.INVOICE_ISSUED); // zero-total PAID branch stays silent
        }
        invoices.save(invoice);
        if (automatic) {
            // ISSUED already tells the tenant; do not announce this invoice's overdue, fee or action window again.
            reconcile(invoice, false);
            autoDeferIdleInvoice(invoice, false);
            processMilestones(contract, invoice, false);
        }
        return response(invoice);
    }

    @Transactional
    public void syncContract(String contractId) {
        Contract contract = contracts.findByIdForUpdate(contractId).orElse(null);
        if (contract != null && contract.getTerminationProposalInvoiceId() != null
                && unilateralFor(contract, contract.getTerminationProposalInvoiceId()) != null)
            reconcile(lockInvoice(contract.getTerminationProposalInvoiceId()));
        if (!billable(contract)) return;
        RentalRequest rental = rentalRequests.findById(contract.getRentalRequestId()).orElse(null);
        if (rental == null || rental.getMoveInDate() == null || leaseMonths(contract, rental) == null) return;
        LocalDate start = rental.getMoveInDate();
        for (int index = 0; index < leaseMonths(contract, rental); index++) {
            LocalDate end = start.plusMonths(index + 1);
            if(contract.getAgreedEarlyEndExclusive()!=null&&end.isAfter(contract.getAgreedEarlyEndExclusive()))end=contract.getAgreedEarlyEndExclusive();
            if (end.minusDays(1).isAfter(time.today())) break;
            int period = index;
            if (invoices.findByContractIdAndPeriodIndex(contractId, period).isEmpty()) {
                MonthlyInvoice draft = invoices.saveAndFlush(MonthlyInvoice.builder()
                        .contractId(contractId).periodIndex(period).periodStart(start.plusMonths(period))
                        .periodEndExclusive(end).status(MonthlyInvoiceStatus.DRAFT)
                        .totalAmount(BigDecimal.ZERO).build());
                notifications.enqueueLandlord(contract, draft, Event.INVOICE_DRAFT_READY);
            }
        }
        for (MonthlyInvoice invoice : invoices.findByContractIdOrderByPeriodIndexDesc(contractId)) {
            MonthlyInvoice locked = lockInvoice(invoice.getId());
            reconcile(locked);
            autoDeferIdleInvoice(locked);
            processMilestones(contract, locked, true);
        }
    }

    /** Uses the database invariant to make retries and multiple scheduler instances safe. */
    @Scheduled(fixedDelayString = "${homespace.billing.sync-delay-ms:60000}")
    public void scheduledSync() {
        String afterId = null;
        while (true) {
            // Closing contracts removes them from this result set. Offset pagination would skip later rows.
            var ids = contracts.findLifecycleIdsAfter(Set.of(ContractStatus.ACTIVE, ContractStatus.EXPIRED), afterId, PageRequest.of(0,100));
            if (ids.isEmpty()) break;
            for (String id : ids) {
                try {
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> syncContract(id));
                    autoIssueDue(id);
                } catch (Exception ex) {
                    log.error("Monthly billing sync failed for contract {}", id, ex);
                }
            }
            afterId = ids.getLast();
        }
    }

    private void reconcile(MonthlyInvoice invoice) { reconcile(invoice, true); }

    /** @param announce false only for the invoice issued in this very transaction (its ISSUED notice covers current state). */
    private void reconcile(MonthlyInvoice invoice, boolean announce) {
        Contract noticeContract = contracts.findById(invoice.getContractId()).orElse(null);
        var notice = unilateralFor(noticeContract, invoice.getId());
        if (notice != null && notice.pending()) {
            payments.lockMonthlyPayment(invoice.getId());
            syncUnilateral(noticeContract, invoice, payments.findByInvoiceId(invoice.getId()).orElse(null));
        }
        if (invoice.getStatus() == MonthlyInvoiceStatus.PAID || invoice.getStatus() == MonthlyInvoiceStatus.DRAFT
                || invoice.getStatus() == MonthlyInvoiceStatus.ROLLED_OVER || invoice.getStatus() == MonthlyInvoiceStatus.CANCELLED) return;
        if (invoice.getPaymentRequestId() == null) return;
        // Also needed when reconciling the previous bill during next-period issue.
        payments.lockMonthlyPayment(invoice.getId());
        Optional<PaymentRequest> payment = payments.findByInvoiceId(invoice.getId());
        boolean tenantInformed = false; // one tenant notice per reconcile: expiry wins over overdue and fee notices
        PaymentExtension request = pendingExtension(invoice).orElse(null);
        if (request != null) {
            boolean settled = payment.map(p -> p.getStatus() == PaymentStatus.CONFIRMED).orElse(false);
            if (settled || !time.now().isBefore(request.respondBy())) {
                replaceExtension(invoice, request.decide(settled ? PaymentExtension.Status.CLOSED : PaymentExtension.Status.EXPIRED,
                        time.now(), "SYSTEM", null));
                invoices.save(invoice);
                if (!settled) { // a paid request closes silently: the receipt notice already told the payer
                    notifyTenant(invoice, Event.INVOICE_EXTENSION_EXPIRED);
                    tenantInformed = true;
                }
            }
        }
        if ("PAYMENT_EXTENSION".equals(invoice.getResolutionMode()) && !extensionActive(invoice)) {
            invoice.setResolutionMode(null); invoices.save(invoice);
        }
        if (payment.isPresent() && payment.get().getStatus() == PaymentStatus.CONFIRMED) {
            invoice.setStatus(MonthlyInvoiceStatus.PAID);
            invoice.setPaidAt(payment.get().getConfirmedAt());
            invoice.setResolutionMode(null);
            invoices.save(invoice);
            Contract contract = contracts.findById(invoice.getContractId()).orElse(null);
            if (contract != null && invoice.getId().equals(contract.getTerminationProposalInvoiceId())
                    && activeTerminationProposal(contract)) {
                contract.setTerminationCancelledAt(time.now());
                contracts.save(contract);
                terminationNotices.tenant(contract, com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_WITHDRAWN);
                log.info("BILLING_TERMINATION_CANCELLED_BY_PAYMENT contract={} invoice={}",
                        contract.getId(), invoice.getId());
            }
        } else if (payment.isPresent() && payment.get().getStatus() != PaymentStatus.TRANSFER_REPORTED
                && payment.get().getStatus() != PaymentStatus.DISPUTED
                && contracts.findById(invoice.getContractId())
                    .map(this::billable).orElse(false)
                && invoice.getDueAt() != null && invoice.getDueAt().isBefore(time.now())) {
            boolean becameOverdue = invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE;
            if (becameOverdue) {
                invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
                invoices.save(invoice);
            }
            payments.markMonthlyOverdue(invoice.getId(), time.now());
            boolean feeIncreased = accrueLateFee(invoice, payment.get());
            if (announce && becameOverdue) {
                if (tenantInformed) notifyLandlord(invoice, Event.INVOICE_OVERDUE);
                else notifyBoth(invoice, Event.INVOICE_OVERDUE);
                tenantInformed = true;
            }
            if (announce && feeIncreased && !tenantInformed) notifyTenant(invoice, Event.INVOICE_AMOUNT_UPDATED);
        }
    }

    /** Same contract/invoice/payment locks as manual decisions; no consent is invented. */
    private void autoDeferIdleInvoice(MonthlyInvoice invoice) { autoDeferIdleInvoice(invoice, true); }

    /** @param announceTenant false only for the invoice issued in this very transaction: its ISSUED notice is the tenant's single message. */
    private void autoDeferIdleInvoice(MonthlyInvoice invoice, boolean announceTenant) {
        if (invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE
                || invoice.getDueAt() == null || time.now().isBefore(MonthlyBillingSchedule.autoRolloverAt(invoice.getDueAt()))
                || invoice.getDeferredAt() != null || pendingExtension(invoice).isPresent()) return;
        Contract contract = contracts.findById(invoice.getContractId()).orElse(null);
        if (!canResolveOverdue(contract, invoice)) return;
        RentalRequest rental = rentalRequests.findById(contract.getRentalRequestId()).orElse(null);
        // Never manufacture a fourth bill or rewrite an already issued next bill.
        if (rental == null || leaseMonths(contract, rental) == null
                || invoice.getPeriodIndex() + 1 >= leaseMonths(contract, rental)
                || invoices.findByContractIdAndPeriodIndex(contract.getId(), invoice.getPeriodIndex() + 1)
                    .filter(next -> next.getStatus() != MonthlyInvoiceStatus.DRAFT).isPresent()) return;

        invoice.setDeferredAt(time.now());
        invoice.setResolutionMode("ROLLOVER");
        List<OverdueAction> history = new ArrayList<>(overdueActions(invoice));
        history.add(new OverdueAction(UUID.randomUUID().toString(), OverdueAction.Type.AUTOMATIC_ROLLOVER,
                "Hệ thống tự chốt công nợ để cộng dồn sang kỳ sau sau 10 ngày quá hạn chưa có phương án xử lý. "
                        + "Phí phạt dừng tăng; QR cũ chỉ hủy khi hóa đơn kỳ sau phát hành.",
                null, time.now(), "SYSTEM", null, null));
        invoice.setOverdueActionsSnapshot(write(history));
        invoices.save(invoice);
        if (announceTenant) notifyBoth(invoice, Event.INVOICE_DEFERRED);
        else notifyLandlord(invoice, Event.INVOICE_DEFERRED); // the owner was not told about the issue, so still learns the decision
        log.info("BILLING_AUTO_DEFERRED contract={} invoice={} amount={} fee={}",
                invoice.getContractId(), invoice.getId(), invoice.getTotalAmount(), fee(invoice));
    }

    private void autoIssueDue(String contractId) {
        Contract contract = contracts.findById(contractId).orElse(null);
        if (!billable(contract)) return;
        List<MonthlyInvoice> ordered = new ArrayList<>(invoices.findByContractIdOrderByPeriodIndexDesc(contractId));
        ordered.sort(Comparator.comparingInt(MonthlyInvoice::getPeriodIndex));
        for (MonthlyInvoice invoice : ordered) {
            if (invoice.getStatus() != MonthlyInvoiceStatus.DRAFT
                    || time.now().isBefore(meterDeadline(invoice))) continue;
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    MonthlyInvoice draft = lockInvoice(invoice.getId());
                    if (draft.getStatus() != MonthlyInvoiceStatus.DRAFT) return;
                    fillMissingMeters(contract, draft);
                    if (!autoReady(contract, draft)) return;
                    List<IssueMonthlyInvoiceRequest.ExtraCharge> extras = readList(draft.getDraftExtraChargesSnapshot(),
                            new TypeReference<List<IssueMonthlyInvoiceRequest.ExtraCharge>>() {});
                    issue(draft.getId(), contract.getLandlordId(),
                            new IssueMonthlyInvoiceRequest(draft.getElectricityEnd(), draft.getWaterEnd(), extras), true);
                    log.info("BILLING_AUTO_ISSUED contract={} invoice={} period={}",
                            contractId, draft.getId(), draft.getPeriodIndex());
                });
            } catch (Exception ex) {
                log.error("BILLING_AUTO_ISSUE_FAILED contract={} invoice={}", contractId, invoice.getId(), ex);
            }
        }
    }

    private boolean autoReady(Contract contract, MonthlyInvoice invoice) {
        if (invoice.getPeriodIndex() > 0) {
            MonthlyInvoice previous = invoices.findByContractIdAndPeriodIndex(
                    contract.getId(), invoice.getPeriodIndex() - 1).orElse(null);
            if (previous == null || previous.getStatus() == MonthlyInvoiceStatus.DRAFT) return false;
        }
        ContractRevision revision = costRevision(contract, invoice);
        if (revision == null) return false;
        List<Map<String, Object>> charges = readList(revision.getChargesSnapshot(),
                new TypeReference<List<Map<String, Object>>>() {});
        return charges.stream().noneMatch(c -> "STATE_WATER_RATE".equals(c.get("billingMethod"))
                    && !Boolean.TRUE.equals(c.get("includedInRent")))
                && (charges.stream().noneMatch(c -> "PER_KWH".equals(c.get("billingMethod"))
                    && !Boolean.TRUE.equals(c.get("includedInRent"))) || invoice.getElectricityEnd() != null)
                && (charges.stream().noneMatch(c -> "PER_M3".equals(c.get("billingMethod"))
                    && !Boolean.TRUE.equals(c.get("includedInRent"))) || invoice.getWaterEnd() != null);
    }

    private void processMilestones(Contract contract, MonthlyInvoice invoice, boolean announce) {
        Instant now = time.now();
        boolean changed = false;
        if (invoice.getStatus() == MonthlyInvoiceStatus.DRAFT) {
            Instant periodLastDay = invoice.getPeriodEndExclusive().minusDays(1)
                    .atStartOfDay(BillingTime.ZONE).toInstant();
            if (invoice.getMeterReminderLoggedAt() == null && !now.isBefore(periodLastDay)) {
                invoice.setMeterReminderLoggedAt(now);
                changed = true;
                log.info("BILLING_METER_REMINDER landlord={} contract={} invoice={} deadline={}",
                        contract.getLandlordId(), contract.getId(), invoice.getId(), meterDeadline(invoice));
            }
            if (invoice.getMeterDeadlineLoggedAt() == null && !now.isBefore(meterDeadline(invoice))) {
                invoice.setMeterDeadlineLoggedAt(now);
                changed = true;
                if (!autoReady(contract, invoice))
                    log.warn("BILLING_METER_REQUIRED landlord={} contract={} invoice={} deadline={}",
                            contract.getLandlordId(), contract.getId(), invoice.getId(), meterDeadline(invoice));
                else log.info("BILLING_METER_DEADLINE contract={} invoice={} ready=true", contract.getId(), invoice.getId());
            }
        } else if (invoice.getStatus() == MonthlyInvoiceStatus.UNPAID && invoice.getDueAt() != null
                && invoice.getPaymentReminderLoggedAt() == null
                && !now.isBefore(invoice.getDueAt().atZone(BillingTime.ZONE).toLocalDate()
                        .minusDays(2).atStartOfDay(BillingTime.ZONE).toInstant())
                && !now.isAfter(invoice.getDueAt())) {
            invoice.setPaymentReminderLoggedAt(now);
            changed = true;
            log.info("BILLING_PAYMENT_REMINDER tenant={} contract={} invoice={} amount={} due={}",
                    contract.getTenantId(), contract.getId(), invoice.getId(), invoice.getTotalAmount(), invoice.getDueAt());
        }
        if (invoice.getStatus() == MonthlyInvoiceStatus.OVERDUE && invoice.getDueAt() != null
                && invoice.getOverdueActionLoggedAt() == null
                && !now.isBefore(actionRequiredAt(invoice))
                && payments.findByInvoiceId(invoice.getId()).map(p -> p.getStatus() != PaymentStatus.TRANSFER_REPORTED
                    && p.getStatus() != PaymentStatus.DISPUTED).orElse(false)) {
            invoice.setOverdueActionLoggedAt(now);
            changed = true;
            log.warn("BILLING_OVERDUE_ACTION_REQUIRED landlord={} tenant={} contract={} invoice={} amount={}",
                    contract.getLandlordId(), contract.getTenantId(), contract.getId(), invoice.getId(), invoice.getTotalAmount());
            // Marker and log are unchanged; only a landlord who still has a decision to make is told.
            if (announce && invoice.getDeferredAt() == null && !activeTerminationProposal(contract)
                    && !extensionActive(invoice) && pendingExtension(invoice).isEmpty())
                notifications.enqueueLandlord(contract, invoice, Event.INVOICE_OVERDUE_ACTION_REQUIRED);
        }
        if (changed) invoices.save(invoice);
    }

    /** @return true only when the payment total (and QR) actually increased. */
    private boolean accrueLateFee(MonthlyInvoice invoice, PaymentRequest payment) {
        if (invoice.getDeferredAt() != null || invoice.isLateFeeExempt() || pendingExtension(invoice).isPresent()) return false;
        if (!List.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE,
                PaymentStatus.REJECTED).contains(payment.getStatus())) return false;
        Contract contract = contracts.findById(invoice.getContractId()).orElse(null);
        if (contract == null) return false;
        if (contract.getTerminationAcceptedAt() != null && contract.getTerminationCompletedAt() == null) return false;
        ContractRevision revision = costRevision(contract, invoice);
        if (revision == null) return false;
        Map<String, Object> policies = read(revision.getPoliciesSnapshot(), new TypeReference<Map<String, Object>>() {});
        LatePaymentPolicy policy = LatePaymentPolicy.from(policies);
        PaymentExtension approved=extensions(invoice).stream().filter(r->r.status()==PaymentExtension.Status.APPROVED)
                .reduce((earlier,later)->later).orElse(null);
        InvoiceLine calculated;
        if(approved!=null) {
            // Missing metadata belongs to an older decision; never retroactively introduce a fee.
            if(!Boolean.TRUE.equals(approved.lateFeeResumesAfterDue()) || !Objects.equals(approved.newDueAt(),invoice.getDueAt()))return false;
            calculated=resumedExtensionFee(policy,approved,time.now());
        }else {
            Instant feeDueAt=invoice.getOriginalDueAt()==null?invoice.getDueAt():invoice.getOriginalDueAt();
            boolean perDay=policy.mode()==LatePaymentPolicy.Mode.FIXED_PER_DAY;
            calculated=new InvoiceLine("LATE_FEE","Phí chậm thanh toán",
                    perDay?BigDecimal.valueOf(policy.chargedDays(feeDueAt,time.now(),BillingTime.ZONE)):BigDecimal.ONE,
                    policy.amount(),policy.accrued(feeDueAt,time.now(),BillingTime.ZONE),perDay?"ngày":"lần");
        }
        BigDecimal accrued=calculated.amount();
        BigDecimal previous = invoice.getLateFeeAmount() == null ? BigDecimal.ZERO : invoice.getLateFeeAmount();
        if (accrued.compareTo(previous) <= 0) return false;
        BigDecimal base = invoice.getBaseAmount() == null
                ? invoice.getTotalAmount().subtract(previous) : invoice.getBaseAmount();
        List<InvoiceLine> lines = new ArrayList<>(readList(invoice.getLineItemsSnapshot(),
                new TypeReference<List<InvoiceLine>>() {}));
        lines.removeIf(line -> "LATE_FEE".equals(line.type()));
        lines.add(calculated);
        BigDecimal newTotal = base.add(accrued);
        String snapshot = write(lines);
        if (payments.increaseMonthlyPayment(invoice.getId(), newTotal, snapshot)) {
            invoice.setBaseAmount(base);
            invoice.setLateFeeAmount(accrued);
            invoice.setLineItemsSnapshot(snapshot);
            invoice.setTotalAmount(newTotal);
            invoices.save(invoice);
            log.info("BILLING_LATE_FEE_UPDATED contract={} invoice={} previous={} fee={} total={}",
                    contract.getId(), invoice.getId(), previous, accrued, newTotal);
            return true;
        }
        return false;
    }

    /** Add only newly overdue days to the reviewed amount; the invoice-wide day/money cap never resets. */
    private InvoiceLine resumedExtensionFee(LatePaymentPolicy policy,PaymentExtension approved,Instant asOf) {
        BigDecimal frozen=approved.frozenLateFee();
        if(frozen==null||frozen.signum()<0)throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        if(policy.mode()!=LatePaymentPolicy.Mode.FIXED_PER_DAY) {
            BigDecimal total=frozen.max(policy.accrued(approved.newDueAt(),asOf,BillingTime.ZONE));
            return new InvoiceLine("LATE_FEE","Phí chậm thanh toán",BigDecimal.ONE,policy.amount(),total,"lần");
        }
        long days=policy.chargedDays(approved.newDueAt(),asOf,BillingTime.ZONE);
        BigDecimal cap=policy.amount().multiply(BigDecimal.valueOf(MonthlyBillingSchedule.MAXIMUM_PENALTY_DAYS));
        if(policy.cap()!=null)cap=cap.min(policy.cap());
        BigDecimal total=frozen.add(policy.amount().multiply(BigDecimal.valueOf(days))).min(cap);
        BigDecimal[] frozenDays=frozen.divideAndRemainder(policy.amount());
        if(frozenDays[1].signum()!=0) {
            // An old aggregate/discounted amount cannot establish a truthful day count.
            return new InvoiceLine("LATE_FEE","Phí chậm thanh toán",BigDecimal.ONE,total,total);
        }
        BigDecimal quantity=frozenDays[0].add(BigDecimal.valueOf(days)).min(BigDecimal.valueOf(MonthlyBillingSchedule.MAXIMUM_PENALTY_DAYS));
        return new InvoiceLine("LATE_FEE","Phí chậm thanh toán",quantity,policy.amount(),total,"ngày");
    }

    private Instant meterDeadline(MonthlyInvoice invoice) {
        return invoice.getPeriodEndExclusive().atStartOfDay(BillingTime.ZONE).toInstant();
    }

    private void fillMissingMeters(Contract contract, MonthlyInvoice invoice) {
        ContractRevision revision = costRevision(contract, invoice);
        RentalRequest rental = rentalRequests.findById(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
        List<Map<String, Object>> charges = readList(revision.getChargesSnapshot(), new TypeReference<List<Map<String, Object>>>() {});
        if (invoice.getElectricityEnd() == null && meterRate(charges, rental, "PER_KWH") != null) {
            BigDecimal start = meterStart(contract, invoice, "electricityInitial", true);
            validateReading(start, start); invoice.setElectricityEnd(start); invoice.setElectricityAutoFilled(true);
        }
        if (invoice.getWaterEnd() == null && meterRate(charges, rental, "PER_M3") != null) {
            BigDecimal start = meterStart(contract, invoice, "waterInitial", false);
            validateReading(start, start); invoice.setWaterEnd(start); invoice.setWaterAutoFilled(true);
        }
    }

    private Instant actionRequiredAt(MonthlyInvoice invoice) {
        return MonthlyBillingSchedule.actionRequiredAt(invoice.getDueAt());
    }

    private void validateExtras(List<IssueMonthlyInvoiceRequest.ExtraCharge> extras) {
        if (extras == null) return;
        if (extras.size() > 20) throw new AppException(ContractErrorCode.INVOICE_AMOUNT_INVALID);
        for (IssueMonthlyInvoiceRequest.ExtraCharge extra : extras) {
            if (extra == null || extra.description() == null || extra.description().isBlank()
                    || extra.description().length() > 150 || extra.amount() == null
                    || extra.amount().signum() <= 0 || extra.amount().stripTrailingZeros().scale() > 0)
                throw new AppException(ContractErrorCode.INVOICE_AMOUNT_INVALID);
        }
    }

    private BigDecimal meterStart(Contract contract, MonthlyInvoice invoice, String key, boolean electric) {
        if (invoice.getPeriodIndex() > 0) {
            MonthlyInvoice previous = invoices.findByContractIdAndPeriodIndex(contract.getId(), invoice.getPeriodIndex() - 1)
                    .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
            BigDecimal value = electric ? previous.getElectricityEnd() : previous.getWaterEnd();
            if (value == null) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            return value;
        }
        ContractRevision revision = revisions.findById(contract.getCurrentRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
        Map<String, Object> meters = read(revision.getInitialMetersSnapshot(), new TypeReference<Map<String, Object>>() {});
        Object value = meters == null ? null : meters.get(key);
        if (value == null || value.toString().isBlank()) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException ex) { throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE); }
    }

    private BigDecimal meterRate(List<Map<String, Object>> charges, RentalRequest rental, String method) {
        List<Map<String, Object>> matching = charges.stream()
                .filter(c -> method.equals(c.get("billingMethod")) && !Boolean.TRUE.equals(c.get("includedInRent"))).toList();
        if (matching.isEmpty()) return null;
        if (matching.size() != 1) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        Map<String, Object> signed = matching.getFirst();
        BigDecimal rate = decimal(signed.get("unitAmount"));
        if (rate == null) {
            // Legacy signed revisions lack a numeric rate. Use the frozen rental-request rate only
            // when the signed charge description is unchanged from the frozen snapshot.
            List<ExcludedChargeItem> excluded = readList(rental.getExcludedChargesSnapshot(),
                    new TypeReference<List<ExcludedChargeItem>>() {});
            List<ExcludedChargeItem> candidates = excluded.stream()
                    .filter(c -> method.equals(c.billingMethod())
                            && Objects.equals(c.chargeType(), signed.get("chargeType"))
                            && Objects.equals(c.displayName(), signed.get("name"))
                            && Objects.equals(c.reason(), signed.get("amountAndMethod"))).toList();
            if (candidates.size() != 1) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            rate = candidates.getFirst().unitAmount();
        }
        if (rate == null || rate.signum() < 0) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        if (!String.valueOf(signed.get("amountAndMethod")).contains(RentalCostCalculator.formatVND(rate))
                && !String.valueOf(signed.get("amountAndMethod")).contains(ContractRenderService.formatVND(rate)))
            throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        return rate;
    }

    private BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE); }
    }

    private boolean verifyInitialPeriodSettled(Contract contract, Map<String, Object> financial,
                                             List<Map<String, Object>> signedCharges) {
        PaymentRequest initial = payments.findByRentalRequestIdAndType(contract.getRentalRequestId(), PaymentType.INITIAL)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
        if (initial.getStatus() != PaymentStatus.CONFIRMED)
            throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        PaymentRequestResponse detail = payments.toResponse(initial, contract.getTenantId());
        BigDecimal paidRent = detail.lineItems().stream().filter(l -> "RENT".equals(l.type()))
                .map(l -> l.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paidFees = detail.lineItems().stream().filter(l -> "SERVICE_FEE".equals(l.type()))
                .map(l -> l.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal signedRent = decimal(financial.get("amountValue"));
        BigDecimal signedFees = signedCharges.stream()
                .filter(c -> !Boolean.TRUE.equals(c.get("includedInRent")))
                .map(c -> decimal(c.get("estimatedMonthlyAmount")))
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (signedRent == null || paidRent.compareTo(signedRent) != 0
                || (paidFees.signum() > 0 && paidFees.compareTo(signedFees) != 0))
            throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        return paidFees.signum() > 0;
    }

    private void validateReading(BigDecimal start, BigDecimal end) {
        if (start == null || end == null || start.signum() < 0 || end.compareTo(start) < 0
                || end.stripTrailingZeros().scale() > 3)
            throw new AppException(ContractErrorCode.INVOICE_METER_INVALID);
    }

    private Integer leaseMonths(Contract c, RentalRequest r) { return c.getAgreedEarlyBillingMonths()!=null?c.getAgreedEarlyBillingMonths():c.getEffectiveLeaseMonths()==null?r.getLeaseMonths():c.getEffectiveLeaseMonths(); }
    private boolean billable(Contract c) { return c!=null && !com.hs.contract.deposit.DepositAgreementService.isDeposit(c) && c.getParentContractId()==null
            && (c.getStatus()==ContractStatus.ACTIVE || c.getStatus()==ContractStatus.EXPIRED); }

    private Contract requireParty(String id, String actor) {
        Contract contract = contracts.findById(id).orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (com.hs.contract.deposit.DepositAgreementService.isDeposit(contract))
            throw new AppException(ContractErrorCode.CONTRACT_SIGNING_NOT_ALLOWED, "Hồ sơ cọc không có hóa đơn thuê hàng tháng.");
        if (!actor.equals(contract.getLandlordId()) && !actor.equals(contract.getTenantId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        return contract;
    }

    private MonthlyInvoiceResponse response(MonthlyInvoice i) {
        Contract contract = contracts.findById(i.getContractId()).orElse(null);
        RentalRequest rental = contract == null ? null : rentalRequests.findById(contract.getRentalRequestId()).orElse(null);
        List<InvoiceLine> displayLines = displayInvoiceLines(i, contract);
        BillingPeriodView billingPeriod = BillingPeriodView.from(i,
                rental == null ? null : leaseMonths(contract, rental), rental == null ? null : rental.getMoveInDate(),
                displayLines.stream().anyMatch(line -> "RENT".equals(line.type())));
        boolean canDefer = contract != null && rentalRequests.findById(contract.getRentalRequestId())
                .map(r -> leaseMonths(contract, r) != null && i.getPeriodIndex() + 1 < leaseMonths(contract, r))
                .orElse(false)
                && invoices.findByContractIdAndPeriodIndex(i.getContractId(), i.getPeriodIndex() + 1)
                    .map(next -> next.getStatus() == MonthlyInvoiceStatus.DRAFT).orElse(true);
        DepositRecord depositRecord = contract == null ? null : deposits.findByRentalRequestId(
                contract.getRentalRequestId()).orElse(null);
        BigDecimal originalDeposit = depositRecord == null ? null : depositRecord.getOriginalAmount();
        BigDecimal retainedDeposit = depositRecord != null
                && depositRecord.getStatus() == DepositStatus.RETAINED_BY_LANDLORD
                ? depositRecord.getRetainedAmount()!=null?depositRecord.getRetainedAmount():depositRecord.getOriginalAmount() : null;
        var unilateral = unilateralFor(contract, i.getId());
        return new MonthlyInvoiceResponse(i.getId(), i.getContractId(), i.getPeriodIndex(),
                i.getPeriodStart(), i.getPeriodEndExclusive(), i.getStatus(), i.getElectricityStart(),
                i.getElectricityEnd(), i.getWaterStart(), i.getWaterEnd(),
                displayLines,
                i.getTotalAmount(), i.getPaymentRequestId(), i.getIssuedAt(), i.getDueAt(), i.getPaidAt(),
                i.getLateFeeAmount() == null ? BigDecimal.ZERO : i.getLateFeeAmount(),
                time.now(), meterDeadline(i), workflowState(i),
                readList(i.getDraftExtraChargesSnapshot(),
                        new TypeReference<List<IssueMonthlyInvoiceRequest.ExtraCharge>>() {}), overdueActions(i),
                i.getDeferredAt(), i.getRolledToInvoiceId(),
                contract == null ? null : contract.getTerminationProposalInvoiceId(),
                contract == null ? null : contract.getTerminationProposedAt(),
                contract == null ? null : contract.getTerminationAcceptedAt(),
                contract == null ? null : contract.getTerminationDeclinedAt(),
                contract == null ? null : contract.getTerminationCancelledAt(),
                contract == null ? null : contract.getTerminationCompletedAt(), originalDeposit,
                retainedDeposit, canDefer,
                contract != null && signedLandlordTerminationClause(contract),
                contract == null ? null : contract.getTerminationForcedAt(),
                i.getOriginalDueAt() == null ? i.getDueAt() : i.getOriginalDueAt(), i.isLateFeeExempt(),
                i.isElectricityAutoFilled(), i.isWaterAutoFilled(), extensions(i), activeResolution(contract, i),
                canRequestExtension(contract, i), canResolveOverdue(contract, i), i.isFinalCostSupplement(),i.getSourceInvoiceId(), billingPeriod,
                unilateral, canRequestTerminationReview(contract,i,unilateral),
                unilateral != null && unilateral.status() == UnilateralTerminationNotice.Status.REVIEW_REQUESTED
                    && billable(contract) && unsettledAndUnreported(i.getId()),
                unilateral != null && unilateral.status() == UnilateralTerminationNotice.Status.ENDED
                    && unilateral.handoverAt() == null && contract.getStatus() == ContractStatus.TERMINATED);
    }

    /** Read-only presentation of old lump-sum fee rows. Never recalculate settled amounts or use today's clock. */
    private ContractRevision costRevision(Contract contract, MonthlyInvoice invoice) {
        if (invoice.getCostTermsRevisionId() != null) return revisions.findById(invoice.getCostTermsRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
        if (terms != null) return terms.at(contract, invoice.getPeriodStart());
        return revisions.findById(contract.getCurrentRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
    }

    private List<InvoiceLine> displayInvoiceLines(MonthlyInvoice invoice, Contract contract) {
        List<InvoiceLine> lines = readList(invoice.getLineItemsSnapshot(), new TypeReference<List<InvoiceLine>>() {});
        if (contract == null || lines.stream().noneMatch(line -> "LATE_FEE".equals(line.type()) && line.quantityUnit() == null))
            return lines;
        ContractRevision revision = costRevision(contract, invoice);
        if (revision == null) return lines;
        LatePaymentPolicy policy;
        try {
            policy = LatePaymentPolicy.from(read(revision.getPoliciesSnapshot(), new TypeReference<Map<String, Object>>() {}));
        } catch (RuntimeException ex) {
            log.warn("BILLING_FEE_DISPLAY_UNAVAILABLE invoice={}", invoice.getId());
            return lines;
        }
        return lines.stream().map(line -> {
            if (!"LATE_FEE".equals(line.type()) || line.quantityUnit() != null || line.amount() == null
                    || line.amount().signum() <= 0 || policy.mode() == LatePaymentPolicy.Mode.NONE
                    || line.quantity() == null || line.quantity().compareTo(BigDecimal.ONE) != 0
                    || line.unitPrice() == null || line.unitPrice().compareTo(line.amount()) != 0) return line;
            if (policy.mode() == LatePaymentPolicy.Mode.FIXED_ONCE && line.amount().compareTo(policy.amount()) == 0)
                return new InvoiceLine(line.type(), line.description(), BigDecimal.ONE, policy.amount(), line.amount(), "lần");
            // A capped legacy amount does not prove how many days were billed; do not invent a count.
            if (policy.mode() != LatePaymentPolicy.Mode.FIXED_PER_DAY
                    || policy.cap() != null && line.amount().compareTo(policy.cap()) >= 0) return line;
            BigDecimal[] division = line.amount().divideAndRemainder(policy.amount());
            if (division[1].signum() != 0) return line;
            return new InvoiceLine(line.type(), line.description(), division[0], policy.amount(), line.amount(), "ngày");
        }).toList();
    }

    private List<OverdueAction> overdueActions(MonthlyInvoice invoice) {
        return readList(invoice.getOverdueActionsSnapshot(), new TypeReference<List<OverdueAction>>() {});
    }

    private boolean unsettledAndUnreported(String invoiceId) {
        return payments.findByInvoiceId(invoiceId).map(p -> List.of(
                PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE, PaymentStatus.REJECTED)
                .contains(p.getStatus())).orElse(false);
    }

    private boolean activeTerminationProposal(Contract contract) {
        return contract.getMutualTerminationWorkflowId()!=null || contract.getTerminationProposedAt() != null && contract.getTerminationDeclinedAt() == null
                && contract.getTerminationCancelledAt() == null && contract.getTerminationCompletedAt() == null;
    }

    private String activeResolution(Contract contract, MonthlyInvoice invoice) {
        if (invoice.getStatus() == MonthlyInvoiceStatus.PAID || invoice.getStatus() == MonthlyInvoiceStatus.CANCELLED) return "NONE";
        if (invoice.getDeferredAt() != null) return "ROLLOVER";
        if (contract != null && activeTerminationProposal(contract)) return "TERMINATION";
        if (extensionActive(invoice)) return "PAYMENT_EXTENSION";
        if (pendingExtension(invoice).isPresent()) return "EXTENSION_REQUEST";
        return "NONE";
    }

    private String workflowState(MonthlyInvoice i) {
        Instant now = time.now();
        if (i.getStatus() == MonthlyInvoiceStatus.CANCELLED) return "CANCELLED";
        if (i.getStatus() == MonthlyInvoiceStatus.PAID) return "PAID";
        if (i.getStatus() == MonthlyInvoiceStatus.ROLLED_OVER) return "ROLLED_OVER";
        if (i.getStatus() == MonthlyInvoiceStatus.DRAFT) {
            if (now.isBefore(i.getPeriodEndExclusive().minusDays(1)
                    .atStartOfDay(BillingTime.ZONE).toInstant())) return "UPCOMING";
            if (i.getDraftExtraChargesSnapshot() != null && now.isBefore(meterDeadline(i)))
                return "READY_FOR_ISSUE";
            if (now.isBefore(meterDeadline(i))) return "METER_REQUIRED";
            return "METER_DEADLINE_MISSED";
        }
        if (payments.findByInvoiceId(i.getId()).map(p -> p.getStatus() == PaymentStatus.TRANSFER_REPORTED
                || p.getStatus() == PaymentStatus.DISPUTED).orElse(false)) return "UNDER_REVIEW";
        if (i.getDeferredAt() != null) return "DEFERRED";
        if (pendingExtension(i).isPresent()) return "EXTENSION_PENDING";
        if (extensionActive(i)) return "PAYMENT_EXTENDED";
        if (i.getStatus() == MonthlyInvoiceStatus.OVERDUE)
            return !now.isBefore(actionRequiredAt(i)) ? "OVERDUE_ACTION_REQUIRED" : "OVERDUE";
        if (i.getDueAt() != null && !now.isBefore(i.getDueAt().atZone(BillingTime.ZONE)
                .toLocalDate().minusDays(2).atStartOfDay(BillingTime.ZONE).toInstant()))
            return "PAYMENT_REMINDER";
        return "UNPAID";
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readValue(json, type); }
        catch (Exception ex) { throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE); }
    }
    private <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readValue(json, type); }
        catch (Exception ex) { throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE); }
    }
    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return List.of();
        return read(json, type);
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE); }
    }
}
