package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.repository.*;
import com.hs.contract.service.engine.VietnameseCurrencyTextConverter;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.receipt.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContractPaymentReceiptContext implements PaymentReceiptContextProvider {
  private final ContractRepository contracts;
  private final ContractRevisionRepository revisions;
  private final MonthlyInvoiceRepository invoices;
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
    return new PaymentReceiptSnapshot.Context(
        c == null ? null : c.getContractNumber(),
        address,
        unit,
        period,
        ownerName,
        tenantName,
        VietnameseCurrencyTextConverter.toWords(p.getTotalAmount()));
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
