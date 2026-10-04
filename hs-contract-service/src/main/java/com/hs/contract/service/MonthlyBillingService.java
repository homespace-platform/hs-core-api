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
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.PaymentRequest;
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
import java.util.*;
import java.math.RoundingMode;

@Service @RequiredArgsConstructor @Slf4j
public class MonthlyBillingService {
    private final ContractRepository contracts;
    private final ContractRevisionRepository revisions;
    private final RentalRequestRepository rentalRequests;
    private final MonthlyInvoiceRepository invoices;
    private final PaymentRequestService payments;
    private final ObjectMapper mapper;
    private final BillingTime time;
    private final PlatformTransactionManager transactionManager;

    /** Restart-safe: unique (contract, period) plus a contract row lock on manual and scheduled sync. */
    @Transactional
    public List<MonthlyInvoiceResponse> list(String contractId, String actorId) {
        Contract contract = requireParty(contractId, actorId);
        if (contract.getStatus() == ContractStatus.ACTIVE) syncContract(contractId);
        return invoices.findByContractIdOrderByPeriodIndexDesc(contractId).stream()
                .map(this::response).toList();
    }

    @Transactional
    public MonthlyInvoiceResponse issue(String invoiceId, String landlordId, IssueMonthlyInvoiceRequest request) {
        MonthlyInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_NOT_FOUND));
        Contract contract = contracts.findById(invoice.getContractId())
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!contract.getLandlordId().equals(landlordId)) throw new AppException(ContractErrorCode.CONTRACT_FORBIDDEN);
        if (contract.getStatus() != ContractStatus.ACTIVE) throw new AppException(ContractErrorCode.INVOICE_NOT_ACTIVE);
        if (invoice.getStatus() != MonthlyInvoiceStatus.DRAFT || invoice.getPeriodEndExclusive().isAfter(time.today()))
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

        if (invoice.getPeriodIndex() > 0) {
            BigDecimal rent = decimal(financial.get("amountValue"));
            if (rent == null || rent.signum() <= 0) throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            if (!String.valueOf(financial.get("amountNumber")).contains(ContractRenderService.formatVND(rent)))
                throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            lines.add(new InvoiceLine("RENT", "Tiền thuê kỳ " + (invoice.getPeriodIndex() + 1),
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
            if (request.extraCharges().size() > 20) throw new AppException(ContractErrorCode.INVOICE_AMOUNT_INVALID);
            for (IssueMonthlyInvoiceRequest.ExtraCharge extra : request.extraCharges()) {
                if (extra == null || extra.description() == null || extra.description().isBlank()
                        || extra.amount() == null || extra.amount().signum() <= 0
                        || extra.amount().stripTrailingZeros().scale() > 0)
                    throw new AppException(ContractErrorCode.INVOICE_AMOUNT_INVALID);
                lines.add(new InvoiceLine("EXTRA", extra.description().trim(), BigDecimal.ONE,
                        extra.amount(), extra.amount()));
            }
        }
        BigDecimal total = lines.stream().map(InvoiceLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() < 0 || total.scale() > 2 && total.stripTrailingZeros().scale() > 2)
            throw new AppException(ContractErrorCode.INVOICE_AMOUNT_INVALID);
        invoice.setLineItemsSnapshot(write(lines));
        invoice.setTotalAmount(total);
        invoice.setIssuedAt(time.now());
        // Existing signed HomeSpace templates say payment by the 5th of each month.
        LocalDate fifth = invoice.getPeriodEndExclusive().withDayOfMonth(5);
        if (fifth.isBefore(invoice.getPeriodEndExclusive())) fifth = fifth.plusMonths(1);
        Instant scheduledDue = fifth.atTime(23, 59, 59).atZone(BillingTime.ZONE).toInstant();
        invoice.setDueAt(scheduledDue.isAfter(time.now().plus(Duration.ofDays(1)))
                ? scheduledDue : time.now().plus(Duration.ofDays(1)));
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
            if (end.isAfter(time.today())) break;
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
                } catch (Exception ex) {
                    log.error("Monthly billing sync failed for contract {}", contract.getId(), ex);
                }
            }
        } while (page.hasNext());
    }

    private void reconcile(MonthlyInvoice invoice) {
        if (invoice.getStatus() == MonthlyInvoiceStatus.PAID || invoice.getStatus() == MonthlyInvoiceStatus.DRAFT) return;
        if (invoice.getPaymentRequestId() == null) return;
        Optional<PaymentRequest> payment = payments.findByInvoiceId(invoice.getId());
        if (payment.isPresent() && payment.get().getStatus() == PaymentStatus.CONFIRMED) {
            invoice.setStatus(MonthlyInvoiceStatus.PAID);
            invoice.setPaidAt(payment.get().getConfirmedAt());
            invoices.save(invoice);
        } else if (payment.isPresent() && payment.get().getStatus() != PaymentStatus.TRANSFER_REPORTED
                && payment.get().getStatus() != PaymentStatus.DISPUTED
                && invoice.getDueAt() != null && invoice.getDueAt().isBefore(time.now())) {
            if (invoice.getStatus() != MonthlyInvoiceStatus.OVERDUE) {
                invoice.setStatus(MonthlyInvoiceStatus.OVERDUE);
                invoices.save(invoice);
            }
            payments.markMonthlyOverdue(invoice.getId(), time.now());
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
        return new MonthlyInvoiceResponse(i.getId(), i.getContractId(), i.getPeriodIndex(),
                i.getPeriodStart(), i.getPeriodEndExclusive(), i.getStatus(), i.getElectricityStart(),
                i.getElectricityEnd(), i.getWaterStart(), i.getWaterEnd(),
                readList(i.getLineItemsSnapshot(), new TypeReference<List<InvoiceLine>>() {}),
                i.getTotalAmount(), i.getPaymentRequestId(), i.getIssuedAt(), i.getDueAt(), i.getPaidAt());
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
