package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.billing.*;
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
    @Value("${homespace.billing.extension-response-hours:48}")
    private int extensionResponseHours = 48;

    @Transactional
    public MonthlyInvoiceResponse requestPaymentExtension(String invoiceId, String tenantId, PaymentExtensionPayload payload) {
        validateExtension(payload);
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), tenantId);
        if (!tenantId.equals(contract.getTenantId())) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        reconcile(invoice);
        if (!canRequestExtension(contract, invoice)) throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED);
        // Reconcile settles fees up to the request instant BEFORE the pause starts.
        List<PaymentExtension> history = new ArrayList<>(extensions(invoice));
        history.add(new PaymentExtension(UUID.randomUUID().toString(), payload.days(), payload.note().trim(),
                PaymentExtension.Status.PENDING, time.now(), time.now().plus(Duration.ofHours(Math.max(1, extensionResponseHours))),
                tenantId, invoice.getDueAt(), fee(invoice), null, null, null));
        invoice.setExtensionRequestsSnapshot(write(history));
        invoices.save(invoice);
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse decidePaymentExtension(String invoiceId, String requestId, String landlordId, boolean approve) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = requireParty(invoice.getContractId(), landlordId);
        requireLandlord(contract, landlordId);
        reconcile(invoice); // Expire pending requests even if the scheduler has not run.
        PaymentExtension pending = pendingExtension(invoice).filter(r -> r.id().equals(requestId))
                .orElseThrow(() -> new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_FOUND));
        if (!billable(contract) || !unsettledAndUnreported(invoiceId)
                || invoice.getDeferredAt() != null || activeTerminationProposal(contract) || extensionActive(invoice))
            throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED);
        if (approve) approveExtension(invoice, pending, landlordId);
        else {
            replaceExtension(invoice, pending.decide(PaymentExtension.Status.REJECTED, time.now(), landlordId, null));
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
        Instant newDue = approvedAt.plus(Duration.ofDays(request.days()));
        // Never let an early request shorten the current payment window.
        if (!newDue.isAfter(invoice.getDueAt())) throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_NOT_ALLOWED,
                "Hạn mới phải sau hạn hiện tại; chọn số ngày lớn hơn hoặc duyệt gần hạn thanh toán.");
        if (invoice.getOriginalDueAt() == null) invoice.setOriginalDueAt(invoice.getDueAt());
        payments.extendMonthlyDueDate(invoice.getId(), newDue, landlordId);
        replaceExtension(invoice, request.decide(PaymentExtension.Status.APPROVED, approvedAt, landlordId, newDue));
        invoice.setLateFeeExempt(true); // Sticky for THIS bill, even after the extended deadline.
        invoice.setDueAt(newDue); invoice.setStatus(MonthlyInvoiceStatus.UNPAID);
        invoice.setResolutionMode("PAYMENT_EXTENSION");
        invoice.setPaymentReminderLoggedAt(null); invoice.setOverdueActionLoggedAt(null);
        log.info("BILLING_EXTENSION_APPROVED invoice={} newDue={} frozenFee={}", invoice.getId(), newDue, fee(invoice));
    }

    private void validateExtension(PaymentExtensionPayload payload) {
        if (payload == null || payload.days() < 1 || payload.days() > 30 || payload.note() == null
                || payload.note().trim().length() < 10 || payload.note().trim().length() > 1000)
            throw new AppException(ContractErrorCode.PAYMENT_EXTENSION_INVALID);
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
        return billable(contract) && invoice.getDueAt() != null
                && List.of(MonthlyInvoiceStatus.UNPAID, MonthlyInvoiceStatus.OVERDUE).contains(invoice.getStatus())
                && invoice.getDeferredAt() == null && !activeTerminationProposal(contract) && !extensionActive(invoice)
                && unsettledAndUnreported(invoice.getId()) && pendingExtension(invoice).isEmpty()
                && extensions(invoice).size() < 100
                && extensions(invoice).stream().noneMatch(r -> Objects.equals(r.baseDueAt(), invoice.getDueAt()));
    }
    private boolean canResolveOverdue(Contract contract, MonthlyInvoice invoice) {
        return billable(contract)
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
        if (!actorId.equals(contract.getLandlordId()) && !actorId.equals(contract.getTenantId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (billable(contract)) syncContract(contractId);
        else if (contract.getStatus() == ContractStatus.TERMINATED)
            invoices.findByContractIdOrderByPeriodIndexDesc(contractId).forEach(i -> reconcile(lockInvoice(i.getId())));
        return invoices.findByContractIdOrderByPeriodIndexDesc(contractId).stream()
                .filter(invoice -> actorId.equals(contract.getLandlordId())
                        || invoice.getStatus() != MonthlyInvoiceStatus.DRAFT)
                .map(this::response).toList();
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
                || request.type() == OverdueAction.Type.AUTOMATIC_ROLLOVER
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
        return response(invoices.save(invoice));
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
        return response(invoices.save(invoice));
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
        return response(invoices.save(invoice));
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
        if (deposit.getStatus() != DepositStatus.HELD || deposit.getHeldAmount() == null
                || deposit.getHeldAmount().compareTo(deposit.getOriginalAmount()) != 0)
            throw new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY);
        rejectPendingForOtherDecision(invoice, landlordId);
        invoice.setResolutionMode("TERMINATION"); invoices.save(invoice);
        contract.setTerminationProposalInvoiceId(invoiceId);
        contract.setTerminationProposedAt(time.now());
        contract.setTerminationAcceptedAt(null);
        contract.setTerminationDeclinedAt(null);
        contract.setTerminationCancelledAt(null);
        contracts.save(contract);
        log.warn("BILLING_TERMINATION_PROPOSED landlord={} tenant={} contract={} invoice={}",
                landlordId, contract.getTenantId(), contract.getId(), invoiceId);
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
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse declineMutualTermination(String invoiceId, String tenantId) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findByIdForUpdate(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!tenantId.equals(contract.getTenantId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
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
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse withdrawMutualTermination(String invoiceId, String landlordId) {
        MonthlyInvoice invoice = lockInvoice(invoiceId);
        Contract contract = contracts.findByIdForUpdate(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!landlordId.equals(contract.getLandlordId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
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
        if (deposit.getStatus() != DepositStatus.HELD || deposit.getHeldAmount() == null
                || deposit.getHeldAmount().compareTo(deposit.getOriginalAmount()) != 0)
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
        if (deposit.getStatus() != DepositStatus.HELD || deposit.getHeldAmount() == null
                || deposit.getHeldAmount().compareTo(deposit.getOriginalAmount()) != 0)
            throw new AppException(ContractErrorCode.TERMINATION_DEPOSIT_NOT_READY);
        finalizeTermination(contract, deposit, landlordId,
                "Chủ nhà chấm dứt theo điều khoản quá hạn trong hợp đồng đã ký sau khi người thuê từ chối đề nghị; đã nhận lại phòng", true);
        log.warn("BILLING_CONTRACT_FORCED_TERMINATION landlord={} tenant={} contract={} invoice={} "
                        + "proposalDeclinedAt={} signedClause=true tenantNotified=true vacantPossession=true depositRetained={}",
                landlordId, contract.getTenantId(), contract.getId(), invoiceId,
                contract.getTerminationDeclinedAt(), deposit.getOriginalAmount());
        return response(invoice);
    }

    private void finalizeTermination(Contract contract, DepositRecord deposit,
                                     String landlordId, String reason, boolean forced) {
        listingStatusService.releaseRentedAfterTermination(contract.getListingId(), landlordId);
        parkingReservations.releaseReservationsForContract(contract.getId());
        deposit.setStatus(DepositStatus.RETAINED_BY_LANDLORD);
        deposit.setHeldAmount(BigDecimal.ZERO);
        deposit.setRefundableAmount(BigDecimal.ZERO);
        deposit.setRetainedAt(time.now());
        deposit.setRetainedReason(reason);
        deposits.save(deposit);
        contract.setStatus(ContractStatus.TERMINATED);
        contract.setTerminationCompletedAt(time.now());
        if (forced) contract.setTerminationForcedAt(time.now());
        contracts.save(contract);
        if(lifecycle!=null) lifecycle.cancelPendingRenewalsForClosedParent(contract);
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
        ContractRevision revision = revisions.findById(contract.getCurrentRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
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
        ContractRevision revision = revisions.findById(contract.getCurrentRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
        Map<String, Object> financial = read(revision.getFinancialSnapshot(),
                new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> signedCharges = readList(revision.getChargesSnapshot(),
                new TypeReference<List<Map<String, Object>>>() {});
        if (financial == null) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
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
            BigDecimal rent = decimal(financial.get("amountValue"));
            if (rent == null || rent.signum() <= 0) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            if (!String.valueOf(financial.get("amountNumber")).contains(ContractRenderService.formatVND(rent)))
                throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            LocalDate nextStart = invoice.getPeriodEndExclusive();
            LocalDate nextEnd = rental.getMoveInDate().plusMonths(invoice.getPeriodIndex() + 2L).minusDays(1);
            lines.add(new InvoiceLine("RENT", "Tiền phòng tháng " + (invoice.getPeriodIndex() + 2)
                    + " (" + nextStart.format(BILLING_DATE) + "–" + nextEnd.format(BILLING_DATE) + ")",
                    BigDecimal.ONE, rent, rent));
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
            if (offset != MonthlyBillingSchedule.PAYMENT_WINDOW_DAYS)
                throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            scheduledDue = MonthlyBillingSchedule.dueAt(invoice.getPeriodEndExclusive());
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
        }
        invoices.save(invoice);
        if (automatic) {
            reconcile(invoice);
            autoDeferIdleInvoice(invoice);
            processMilestones(contract, invoice);
        }
        return response(invoice);
    }

    @Transactional
    public void syncContract(String contractId) {
        Contract contract = contracts.findByIdForUpdate(contractId).orElse(null);
        if (!billable(contract)) return;
        RentalRequest rental = rentalRequests.findById(contract.getRentalRequestId()).orElse(null);
        if (rental == null || rental.getMoveInDate() == null || leaseMonths(contract, rental) == null) return;
        LocalDate start = rental.getMoveInDate();
        for (int index = 0; index < leaseMonths(contract, rental); index++) {
            LocalDate end = start.plusMonths(index + 1);
            if (end.minusDays(1).isAfter(time.today())) break;
            int period = index;
            if (invoices.findByContractIdAndPeriodIndex(contractId, period).isEmpty()) {
                invoices.saveAndFlush(MonthlyInvoice.builder()
                        .contractId(contractId).periodIndex(period).periodStart(start.plusMonths(period))
                        .periodEndExclusive(end).status(MonthlyInvoiceStatus.DRAFT)
                        .totalAmount(BigDecimal.ZERO).build());
            }
        }
        for (MonthlyInvoice invoice : invoices.findByContractIdOrderByPeriodIndexDesc(contractId)) {
            MonthlyInvoice locked = lockInvoice(invoice.getId());
            reconcile(locked);
            autoDeferIdleInvoice(locked);
            processMilestones(contract, locked);
        }
    }

    /** Uses the database invariant to make retries and multiple scheduler instances safe. */
    @Scheduled(fixedDelayString = "${homespace.billing.sync-delay-ms:60000}")
    public void scheduledSync() {
        for (ContractStatus billingStatus : List.of(ContractStatus.ACTIVE, ContractStatus.EXPIRED)) {
        int pageNumber = 0;
        Page<Contract> page;
        do {
            page = contracts.findByStatus(billingStatus, PageRequest.of(pageNumber++, 100));
            if (page == null) break;
            for (Contract contract : page.getContent()) {
                try {
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> syncContract(contract.getId()));
                    autoIssueDue(contract.getId());
                } catch (Exception ex) {
                    log.error("Monthly billing sync failed for contract {}", contract.getId(), ex);
                }
            }
        } while (page.hasNext());
        }
    }

    private void reconcile(MonthlyInvoice invoice) {
        if (invoice.getStatus() == MonthlyInvoiceStatus.PAID || invoice.getStatus() == MonthlyInvoiceStatus.DRAFT
                || invoice.getStatus() == MonthlyInvoiceStatus.ROLLED_OVER) return;
        if (invoice.getPaymentRequestId() == null) return;
        // Also needed when reconciling the previous bill during next-period issue.
        payments.lockMonthlyPayment(invoice.getId());
        Optional<PaymentRequest> payment = payments.findByInvoiceId(invoice.getId());
        pendingExtension(invoice).ifPresent(request -> {
            boolean settled = payment.map(p -> p.getStatus() == PaymentStatus.CONFIRMED).orElse(false);
            if (settled || !time.now().isBefore(request.respondBy())) {
                replaceExtension(invoice, request.decide(settled ? PaymentExtension.Status.CLOSED : PaymentExtension.Status.EXPIRED,
                        time.now(), "SYSTEM", null));
                invoices.save(invoice);
            }
        });
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
                log.info("BILLING_TERMINATION_CANCELLED_BY_PAYMENT contract={} invoice={}",
                        contract.getId(), invoice.getId());
            }
        } else if (payment.isPresent() && payment.get().getStatus() != PaymentStatus.TRANSFER_REPORTED
                && payment.get().getStatus() != PaymentStatus.DISPUTED
                && contracts.findById(invoice.getContractId())
                    .map(this::billable).orElse(false)
                && invoice.getDueAt() != null && invoice.getDueAt().isBefore(time.now())) {
            if (invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE) {
                invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
                invoices.save(invoice);
            }
            payments.markMonthlyOverdue(invoice.getId(), time.now());
            accrueLateFee(invoice, payment.get());
        }
    }

    /** Same contract/invoice/payment locks as manual decisions; no consent is invented. */
    private void autoDeferIdleInvoice(MonthlyInvoice invoice) {
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
        ContractRevision revision = revisions.findById(contract.getCurrentRevisionId()).orElse(null);
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

    private void processMilestones(Contract contract, MonthlyInvoice invoice) {
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
        }
        if (changed) invoices.save(invoice);
    }

    private void accrueLateFee(MonthlyInvoice invoice, PaymentRequest payment) {
        if (invoice.getDeferredAt() != null || invoice.isLateFeeExempt() || pendingExtension(invoice).isPresent()) return;
        if (!List.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE,
                PaymentStatus.REJECTED).contains(payment.getStatus())) return;
        Contract contract = contracts.findById(invoice.getContractId()).orElse(null);
        if (contract == null) return;
        if (contract.getTerminationAcceptedAt() != null && contract.getTerminationCompletedAt() == null) return;
        ContractRevision revision = revisions.findById(contract.getCurrentRevisionId()).orElse(null);
        if (revision == null) return;
        Map<String, Object> policies = read(revision.getPoliciesSnapshot(), new TypeReference<Map<String, Object>>() {});
        LatePaymentPolicy policy = LatePaymentPolicy.from(policies);
        Instant feeDueAt = invoice.getOriginalDueAt() == null ? invoice.getDueAt() : invoice.getOriginalDueAt();
        Instant feeAsOf = time.now();
        BigDecimal accrued = policy.accrued(feeDueAt, feeAsOf, BillingTime.ZONE);
        BigDecimal previous = invoice.getLateFeeAmount() == null ? BigDecimal.ZERO : invoice.getLateFeeAmount();
        if (accrued.compareTo(previous) <= 0) return;
        BigDecimal base = invoice.getBaseAmount() == null
                ? invoice.getTotalAmount().subtract(previous) : invoice.getBaseAmount();
        List<InvoiceLine> lines = new ArrayList<>(readList(invoice.getLineItemsSnapshot(),
                new TypeReference<List<InvoiceLine>>() {}));
        lines.removeIf(line -> "LATE_FEE".equals(line.type()));
        boolean perDay = policy.mode() == LatePaymentPolicy.Mode.FIXED_PER_DAY;
        lines.add(new InvoiceLine("LATE_FEE", "Phí chậm thanh toán",
                perDay ? BigDecimal.valueOf(policy.chargedDays(feeDueAt, feeAsOf, BillingTime.ZONE)) : BigDecimal.ONE,
                policy.amount(), accrued, perDay ? "ngày" : "lần"));
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
        }
    }

    private Instant meterDeadline(MonthlyInvoice invoice) {
        return invoice.getPeriodEndExclusive().atStartOfDay(BillingTime.ZONE).toInstant();
    }

    private void fillMissingMeters(Contract contract, MonthlyInvoice invoice) {
        ContractRevision revision = revisions.findById(contract.getCurrentRevisionId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
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

    private Integer leaseMonths(Contract c, RentalRequest r) { return c.getEffectiveLeaseMonths()==null?r.getLeaseMonths():c.getEffectiveLeaseMonths(); }
    private boolean billable(Contract c) { return c!=null && c.getParentContractId()==null
            && (c.getStatus()==ContractStatus.ACTIVE || c.getStatus()==ContractStatus.EXPIRED); }

    private Contract requireParty(String id, String actor) {
        Contract contract = contracts.findById(id).orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
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
                ? depositRecord.getOriginalAmount() : null;
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
                canRequestExtension(contract, i), canResolveOverdue(contract, i), i.isFinalCostSupplement(),i.getSourceInvoiceId(), billingPeriod);
    }

    /** Read-only presentation of old lump-sum fee rows. Never recalculate settled amounts or use today's clock. */
    private List<InvoiceLine> displayInvoiceLines(MonthlyInvoice invoice, Contract contract) {
        List<InvoiceLine> lines = readList(invoice.getLineItemsSnapshot(), new TypeReference<List<InvoiceLine>>() {});
        if (contract == null || lines.stream().noneMatch(line -> "LATE_FEE".equals(line.type()) && line.quantityUnit() == null))
            return lines;
        ContractRevision revision = revisions.findById(contract.getCurrentRevisionId()).orElse(null);
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
        return contract.getTerminationProposedAt() != null && contract.getTerminationDeclinedAt() == null
                && contract.getTerminationCancelledAt() == null && contract.getTerminationCompletedAt() == null;
    }

    private String activeResolution(Contract contract, MonthlyInvoice invoice) {
        if (invoice.getStatus() == MonthlyInvoiceStatus.PAID) return "NONE";
        if (invoice.getDeferredAt() != null) return "ROLLOVER";
        if (contract != null && activeTerminationProposal(contract)) return "TERMINATION";
        if (extensionActive(invoice)) return "PAYMENT_EXTENSION";
        if (pendingExtension(invoice).isPresent()) return "EXTENSION_REQUEST";
        return "NONE";
    }

    private String workflowState(MonthlyInvoice i) {
        Instant now = time.now();
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
