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

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.math.RoundingMode;

@Service @RequiredArgsConstructor @Slf4j
public class MonthlyBillingService {
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

    /** Restart-safe: unique (contract, period) plus a contract row lock on manual and scheduled sync. */
    @Transactional
    public List<MonthlyInvoiceResponse> list(String contractId, String actorId) {
        Contract contract = requireParty(contractId, actorId);
        if (contract.getStatus() == ContractStatus.ACTIVE) syncContract(contractId);
        return invoices.findByContractIdOrderByPeriodIndexDesc(contractId).stream()
                .filter(invoice -> actorId.equals(contract.getLandlordId())
                        || invoice.getStatus() != MonthlyInvoiceStatus.DRAFT)
                .map(this::response).toList();
    }

    /** Record an escalation after five overdue days; this never changes the lease or payment terms. */
    @Transactional
    public MonthlyInvoiceResponse recordOverdueAction(String invoiceId, String landlordId,
                                                       CreateOverdueActionRequest request) {
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
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
                || request.note().trim().length() < 10 || request.note().trim().length() > 1000
                || request.type() == OverdueAction.Type.EXTENSION_PROPOSAL && request.proposedDate() == null
                || request.proposedDate() != null && request.type() != OverdueAction.Type.EXTENSION_PROPOSAL
                    && request.type() != OverdueAction.Type.MUTUAL_TERMINATION_PROPOSAL
                || request.proposedDate() != null && !request.proposedDate().isAfter(time.today()))
            throw new AppException(ContractErrorCode.OVERDUE_ACTION_INVALID);
        List<OverdueAction> history = new ArrayList<>(overdueActions(invoice));
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
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
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
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
        Contract contract = requireParty(invoice.getContractId(), landlordId);
        if (!landlordId.equals(contract.getLandlordId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        RentalRequest rental = rentalRequests.findById(contract.getRentalRequestId())
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
        if (contract.getStatus() != ContractStatus.ACTIVE || activeTerminationProposal(contract)
                || rental.getLeaseMonths() == null
                || invoice.getPeriodIndex() + 1 >= rental.getLeaseMonths()
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
        invoice.setDeferredAt(time.now());
        log.warn("BILLING_DEFERRED landlord={} tenant={} contract={} invoice={} amount={}",
                landlordId, contract.getTenantId(), contract.getId(), invoiceId, invoice.getTotalAmount());
        return response(invoices.save(invoice));
    }

    /** Proposal alone has no legal or inventory effect. */
    @Transactional
    public MonthlyInvoiceResponse proposeMutualTermination(String invoiceId, String landlordId) {
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
        Contract contract = contracts.findByIdForUpdate(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!landlordId.equals(contract.getLandlordId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (contract.getStatus() != ContractStatus.ACTIVE || invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE
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
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
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
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
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
        contracts.save(contract);
        log.warn("BILLING_TERMINATION_DECLINED tenant={} contract={} invoice={}",
                tenantId, contract.getId(), invoiceId);
        return response(invoice);
    }

    @Transactional
    public MonthlyInvoiceResponse withdrawMutualTermination(String invoiceId, String landlordId) {
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
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
        contracts.save(contract);
        log.warn("BILLING_TERMINATION_WITHDRAWN landlord={} contract={} invoice={}",
                landlordId, contract.getId(), invoiceId);
        return response(invoice);
    }

    /** Complete only after bilateral agreement and actual return of the property. */
    @Transactional
    public MonthlyInvoiceResponse completeMutualTermination(String invoiceId, String landlordId,
                                                             CompleteTerminationRequest request) {
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
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
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
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
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
        Contract contract = contracts.findById(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!contract.getLandlordId().equals(landlordId)) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (contract.getStatus() != ContractStatus.ACTIVE) throw new AppException(ContractErrorCode.INVOICE_NOT_ACTIVE);
        if (invoice.getStatus() != MonthlyInvoiceStatus.DRAFT
                || time.today().isBefore(invoice.getPeriodEndExclusive().minusDays(1)))
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
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
        Contract contract = contracts.findById(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!contract.getLandlordId().equals(landlordId)) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (contract.getStatus() != ContractStatus.ACTIVE) throw new AppException(ContractErrorCode.INVOICE_NOT_ACTIVE);
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
        if (rental.getLeaseMonths() == null || rental.getLeaseMonths() <= invoice.getPeriodIndex()
                || rental.getMoveInDate() == null)
            throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        if (invoice.getPeriodIndex() + 1 < rental.getLeaseMonths()) {
            BigDecimal rent = decimal(financial.get("amountValue"));
            if (rent == null || rent.signum() <= 0) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            if (!String.valueOf(financial.get("amountNumber")).contains(ContractRenderService.formatVND(rent)))
                throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            LocalDate nextStart = invoice.getPeriodEndExclusive();
            LocalDate nextEnd = rental.getMoveInDate().plusMonths(invoice.getPeriodIndex() + 2L).minusDays(1);
            lines.add(new InvoiceLine("RENT", "Tiền thuê kỳ " + (invoice.getPeriodIndex() + 2)
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
            if (previous.getDeferredAt() != null && previous.getStatus() != MonthlyInvoiceStatus.PAID
                    && previous.getStatus() != MonthlyInvoiceStatus.ROLLED_OVER) {
                PaymentStatus oldStatus = payments.findByInvoiceId(previous.getId())
                        .map(PaymentRequest::getStatus).orElse(null);
                if (oldStatus == null || !List.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE,
                        PaymentStatus.REJECTED).contains(oldStatus))
                    throw new AppException(ContractErrorCode.OVERDUE_DEFERRAL_NOT_ALLOWED);
                lines.add(new InvoiceLine("BALANCE_FORWARD", "Công nợ hóa đơn kỳ "
                        + (previous.getPeriodIndex() + 1) + " (gồm phí phạt đã chốt)", BigDecimal.ONE,
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
        invoice.setDueAt(scheduledDue.isAfter(time.now().plus(Duration.ofDays(1)))
                ? scheduledDue : time.now().plus(Duration.ofDays(1)));
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
        return response(invoices.save(invoice));
    }

    @Transactional
    public void syncContract(String contractId) {
        Contract contract = contracts.findByIdForUpdate(contractId).orElse(null);
        if (contract == null || contract.getStatus() != ContractStatus.ACTIVE) return;
        RentalRequest rental = rentalRequests.findById(contract.getRentalRequestId()).orElse(null);
        if (rental == null || rental.getMoveInDate() == null || rental.getLeaseMonths() == null) return;
        LocalDate start = rental.getMoveInDate();
        for (int index = 0; index < rental.getLeaseMonths(); index++) {
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
            reconcile(invoice);
            processMilestones(contract, invoice);
        }
    }

    /** Uses the database invariant to make retries and multiple scheduler instances safe. */
    @Scheduled(fixedDelayString = "${homespace.billing.sync-delay-ms:60000}")
    public void scheduledSync() {
        int pageNumber = 0;
        Page<Contract> page;
        do {
            page = contracts.findByStatus(ContractStatus.ACTIVE, PageRequest.of(pageNumber++, 100));
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

    private void reconcile(MonthlyInvoice invoice) {
        if (invoice.getStatus() == MonthlyInvoiceStatus.PAID || invoice.getStatus() == MonthlyInvoiceStatus.DRAFT
                || invoice.getStatus() == MonthlyInvoiceStatus.ROLLED_OVER) return;
        if (invoice.getPaymentRequestId() == null) return;
        Optional<PaymentRequest> payment = payments.findByInvoiceId(invoice.getId());
        if (payment.isPresent() && payment.get().getStatus() == PaymentStatus.CONFIRMED) {
            invoice.setStatus(MonthlyInvoiceStatus.PAID);
            invoice.setPaidAt(payment.get().getConfirmedAt());
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
                && invoice.getDueAt() != null && invoice.getDueAt().isBefore(time.now())) {
            if (invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE) {
                invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
                invoices.save(invoice);
            }
            payments.markMonthlyOverdue(invoice.getId(), time.now());
            accrueLateFee(invoice, payment.get());
        }
    }

    private void autoIssueDue(String contractId) {
        Contract contract = contracts.findById(contractId).orElse(null);
        if (contract == null || contract.getStatus() != ContractStatus.ACTIVE) return;
        for (MonthlyInvoice invoice : invoices.findByContractIdOrderByPeriodIndexDesc(contractId)) {
            if (invoice.getStatus() != MonthlyInvoiceStatus.DRAFT
                    || time.now().isBefore(meterDeadline(invoice))) continue;
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    MonthlyInvoice draft = invoices.findByIdForUpdate(invoice.getId()).orElse(null);
                    if (draft == null || draft.getStatus() != MonthlyInvoiceStatus.DRAFT || !autoReady(contract, draft)) return;
                    List<IssueMonthlyInvoiceRequest.ExtraCharge> extras = readList(draft.getDraftExtraChargesSnapshot(),
                            new TypeReference<List<IssueMonthlyInvoiceRequest.ExtraCharge>>() {});
                    issue(draft.getId(), contract.getLandlordId(),
                            new IssueMonthlyInvoiceRequest(draft.getElectricityEnd(), draft.getWaterEnd(), extras));
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
        if (invoice.getDeferredAt() != null) return;
        if (!List.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.OVERDUE,
                PaymentStatus.REJECTED).contains(payment.getStatus())) return;
        Contract contract = contracts.findById(invoice.getContractId()).orElse(null);
        if (contract == null) return;
        if (contract.getTerminationAcceptedAt() != null && contract.getTerminationCompletedAt() == null) return;
        ContractRevision revision = revisions.findById(contract.getCurrentRevisionId()).orElse(null);
        if (revision == null) return;
        Map<String, Object> policies = read(revision.getPoliciesSnapshot(), new TypeReference<Map<String, Object>>() {});
        LatePaymentPolicy policy = LatePaymentPolicy.from(policies);
        BigDecimal accrued = policy.accrued(invoice.getDueAt(), time.now(), BillingTime.ZONE);
        BigDecimal previous = invoice.getLateFeeAmount() == null ? BigDecimal.ZERO : invoice.getLateFeeAmount();
        if (accrued.compareTo(previous) <= 0) return;
        BigDecimal base = invoice.getBaseAmount() == null
                ? invoice.getTotalAmount().subtract(previous) : invoice.getBaseAmount();
        List<InvoiceLine> lines = new ArrayList<>(readList(invoice.getLineItemsSnapshot(),
                new TypeReference<List<InvoiceLine>>() {}));
        lines.removeIf(line -> "LATE_FEE".equals(line.type()));
        lines.add(new InvoiceLine("LATE_FEE", "Phí chậm thanh toán", BigDecimal.ONE, accrued, accrued));
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
        return invoice.getPeriodEndExclusive().atTime(10, 0).atZone(BillingTime.ZONE).toInstant();
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

    private Contract requireParty(String id, String actor) {
        Contract contract = contracts.findById(id).orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!actor.equals(contract.getLandlordId()) && !actor.equals(contract.getTenantId()))
            throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        return contract;
    }

    private MonthlyInvoiceResponse response(MonthlyInvoice i) {
        Contract contract = contracts.findById(i.getContractId()).orElse(null);
        boolean canDefer = contract != null && rentalRequests.findById(contract.getRentalRequestId())
                .map(r -> r.getLeaseMonths() != null && i.getPeriodIndex() + 1 < r.getLeaseMonths())
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
                readList(i.getLineItemsSnapshot(), new TypeReference<List<InvoiceLine>>() {}),
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
                contract == null ? null : contract.getTerminationForcedAt());
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
