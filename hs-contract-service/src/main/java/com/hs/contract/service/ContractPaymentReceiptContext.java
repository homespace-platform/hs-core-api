package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.repository.*;
import com.hs.contract.dto.billing.ClosureDeduction;
import com.hs.contract.service.engine.VietnameseCurrencyTextConverter;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.receipt.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContractPaymentReceiptContext implements PaymentReceiptContextProvider {
  private final ContractRepository contracts;
  private final ContractRevisionRepository revisions;
  private final MonthlyInvoiceRepository invoices;
  private final ContractClosureRepository closures;
  private final RentalRequestRepository rentals;
  private final ObjectMapper mapper;

  @Override
  public PaymentReceiptSnapshot.Context context(PaymentRequest p) {
    boolean refund = p.getDirection() == PaymentDirection.LANDLORD_TO_TENANT;
    String owner = refund ? p.getPayerId() : p.getPayeeId(),
        tenant = refund ? p.getPayeeId() : p.getPayerId();
    var rental =
        p.getRentalRequestId() == null
            ? null
            : rentals
                .findById(p.getRentalRequestId())
                .filter(r -> owner.equals(r.getOwnerId()) && tenant.equals(r.getRenterId()))
                .orElse(null);
    var c =
        p.getContractId() == null
            ? null
            : contracts
                .findById(p.getContractId())
                .filter(
                    row -> owner.equals(row.getLandlordId()) && tenant.equals(row.getTenantId()))
                .orElse(null);
    var rev =
        c == null || c.getCurrentRevisionId() == null
            ? null
            : revisions.findById(c.getCurrentRevisionId()).orElse(null);
    var property =
        map(
            rev == null
                ? rental == null ? null : rental.getListingSnapshot()
                : rev.getPropertySnapshot());
    String address = value(property, "fullAddress", "addressText", "address"),
        unit = value(property, "unitNumber", "roomNumber");
    String ownerName = rev == null ? null : value(map(rev.getLandlordSnapshot()), "fullName");
    String tenantName =
        rev == null
            ? rental == null ? null : rental.getRenterName()
            : value(map(rev.getTenantSnapshot()), "fullName");
    String period = null;
    var invoice =
        p.getInvoiceId() == null
            ? null
            : invoices
                .findById(p.getInvoiceId())
                .filter(i -> Objects.equals(i.getContractId(), p.getContractId()))
                .orElse(null);
    var format = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    if (invoice != null) {
      period =
          invoice.getPeriodStart().format(format)
              + " - trước "
              + invoice.getPeriodEndExclusive().format(format);
      if (invoice.getRentPeriodStart() != null
          && invoice.getRentPeriodEndExclusive() != null
          && (!invoice.getRentPeriodStart().equals(invoice.getPeriodStart())
              || !invoice.getRentPeriodEndExclusive().equals(invoice.getPeriodEndExclusive())))
        period =
            "Chi phí: "
                + period
                + "; tiền thuê: "
                + invoice.getRentPeriodStart().format(format)
                + " - trước "
                + invoice.getRentPeriodEndExclusive().format(format);
    } else if (rental != null
        && rental.getMoveInDate() != null
        && p.getType() == com.hs.payment.model.constant.PaymentType.INITIAL)
      period =
          rental.getMoveInDate().format(format)
              + " - trước "
              + rental.getMoveInDate().plusMonths(1).format(format);
    PaymentReceiptSnapshot.Settlement settlement = null;
    var closure = p.getType() == PaymentType.DEPOSIT_REFUND
        ? closures.findByRefundPaymentId(p.getId()).orElseThrow(() -> new IllegalStateException("Missing settlement for deposit refund receipt"))
        : p.getType() == PaymentType.OTHER_CHARGE ? closures.findByBalancePaymentId(p.getId()).orElse(null) : null;
    if (closure != null) {
      List<ClosureDeduction> deductions;
      try {
        deductions = mapper.readValue(closure.getDeductionsSnapshot(), new TypeReference<>() {});
      } catch (Exception e) {
        throw new IllegalStateException("Invalid settlement deductions for deposit refund receipt", e);
      }
      if (deductions == null || deductions.stream().anyMatch(d -> d == null || d.amount() == null
          || d.amount().signum() < 0 || d.description() == null || d.description().isBlank())
          || (p.getType() == PaymentType.DEPOSIT_REFUND
              ? closure.getRefundAmount() == null || closure.getRefundAmount().compareTo(p.getTotalAmount()) != 0
              : closure.getBalanceAmount() == null || closure.getBalanceAmount().compareTo(p.getTotalAmount()) != 0))
        throw new IllegalStateException("Settlement does not match deposit refund receipt");
      var deductionTotal = deductions.stream().map(ClosureDeduction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      var depositAmount = p.getType() == PaymentType.DEPOSIT_REFUND
          ? p.getTotalAmount().add(deductionTotal) : deductionTotal.subtract(p.getTotalAmount());
      if (depositAmount.signum() < 0) throw new IllegalStateException("Settlement deductions are less than balance due");
      settlement = new PaymentReceiptSnapshot.Settlement(depositAmount,
          deductions.stream().map(d -> new PaymentReceiptSnapshot.Deduction(d.description(), d.amount())).toList());
    }
    return new PaymentReceiptSnapshot.Context(
        c == null ? null : c.getContractNumber(),
        address,
        unit,
        period,
        ownerName,
        tenantName,
        VietnameseCurrencyTextConverter.toWords(p.getTotalAmount()), settlement);
  }

  private Map<String, Object> map(String json) {
    try {
      return json == null ? Map.of() : mapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private String value(Map<String, Object> data, String... keys) {
    for (String key : keys) {
      var value = data.get(key);
      if (value instanceof String s && !s.isBlank()) return s;
    }
    return null;
  }
}
