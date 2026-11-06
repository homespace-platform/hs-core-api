package com.hs.payment.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.dto.PageResponse;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.BankAccountSnapshotDto;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import com.hs.user.repository.UserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentReceiptService {
  private final PaymentReceiptRepository receipts;
  private final PaymentLineItemRepository lines;
  private final UserRepository users;
  private final ObjectMapper mapper;
  private final StorageService storage;
  private final StorageObjectRepository objects;

  @Autowired(required = false)
  private PaymentReceiptContextProvider contextProvider;

  /**
   * Local database work only, inside the receipt-confirmation transaction. No PDF/network calls.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void capture(PaymentRequest p) {
    if (p.getStatus() != PaymentStatus.CONFIRMED
        || p.getConfirmedAt() == null
        || p.getTotalAmount() == null
        || p.getTotalAmount().signum() <= 0) return;
    if (receipts.findByPaymentRequestId(p.getId()).isPresent()) return;
    var r = new PaymentReceipt();
    r.setId(UUID.randomUUID().toString());
    r.setPaymentRequestId(p.getId());
    r.setReceiptNumber(
        "HS-"
            + (p.getDirection() == PaymentDirection.LANDLORD_TO_TENANT ? "HT" : "TT")
            + "-"
            + p.getConfirmedAt()
                .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            + "-"
            + r.getId().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT));
    boolean refund = p.getDirection() == PaymentDirection.LANDLORD_TO_TENANT;
    r.setLandlordId(refund ? p.getPayerId() : p.getPayeeId());
    r.setTenantId(refund ? p.getPayeeId() : p.getPayerId());
    r.setIssuedAt(p.getConfirmedAt());
    var context =
        contextProvider == null
            ? PaymentReceiptSnapshot.Context.empty()
            : contextProvider.context(p);
    if (context == null) context = PaymentReceiptSnapshot.Context.empty();
    var payerBank = bank(p.getPayerBankAccountSnapshot());
    var payeeBank = bank(p.getPayeeBankAccountSnapshot());
    var payer =
        new PaymentReceiptSnapshot.Party(
            p.getPayerId(),
            name(p.getPayerId(), refund ? context.landlordName() : context.tenantName(), payerBank),
            payerBank);
    var payee =
        new PaymentReceiptSnapshot.Party(
            p.getPayeeId(),
            name(p.getPayeeId(), refund ? context.tenantName() : context.landlordName(), payeeBank),
            payeeBank);
    var allocated = lines.findByPaymentRequestIdOrderBySortOrderAsc(p.getId());
    boolean valid =
        !allocated.isEmpty()
            && allocated.stream().allMatch(l -> l.getAmount() != null)
            && allocated.stream()
                    .map(PaymentLineItem::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .compareTo(p.getTotalAmount())
                == 0;
    List<PaymentReceiptSnapshot.Line> detail =
        valid
            ? allocated.stream()
                .filter(l -> l.getAmount().signum() != 0)
                .map(
                    l ->
                        new PaymentReceiptSnapshot.Line(
                            text(l.getDisplayName(), "Khoản thanh toán"),
                            l.getAmount(),
                            l.getQuantity(),
                            l.getUnitPrice(),
                            description(l)))
                .toList()
            : List.of(
                new PaymentReceiptSnapshot.Line(
                    refund ? "Hoàn tiền đã xác nhận" : "Khoản thanh toán đã xác nhận",
                    p.getTotalAmount(),
                    1,
                    p.getTotalAmount(),
                    null));
    var snapshot =
        new PaymentReceiptSnapshot(
            1,
            r.getReceiptNumber(),
            p.getId(),
            p.getRentalRequestId(),
            p.getType(),
            p.getDirection(),
            p.getTotalAmount(),
            p.getCurrency(),
            p.getConfirmedAt(),
            p.getPayerReportedAt(),
            p.getTransferReference(),
            p.getBankTransactionReference(),
            payer,
            payee,
            context,
            detail);
    try {
      r.setSnapshotJson(mapper.copy().findAndRegisterModules().writeValueAsString(snapshot));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot freeze payment receipt", e);
    }
    r.setSnapshotHash(hash(r.getSnapshotJson()));
    r.setNextAttemptAt(Instant.now());
    receipts.save(r);
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  PaymentReceiptSnapshot snapshot(PaymentReceipt r) {
    if (!hash(r.getSnapshotJson()).equals(r.getSnapshotHash()))
      throw new IllegalStateException("Receipt snapshot integrity check failed");
    try {
      return mapper
          .copy()
          .findAndRegisterModules()
          .readValue(r.getSnapshotJson(), PaymentReceiptSnapshot.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid receipt snapshot", e);
    }
  }

  private BankAccountSnapshotDto bank(String value) {
    try {
      return value == null ? null : mapper.readValue(value, BankAccountSnapshotDto.class);
    } catch (Exception e) {
      return null;
    }
  }

  private String name(String id, String frozen, BankAccountSnapshotDto bank) {
    if (frozen != null && !frozen.isBlank()) return frozen;
    var user = users.findById(id).orElse(null);
    if (user != null) {
      String name =
          (Objects.toString(user.getFirstName(), "")
                  + " "
                  + Objects.toString(user.getLastName(), ""))
              .trim();
      if (!name.isBlank()) return name;
    }
    return bank != null && !text(bank.accountHolderName(), "").isBlank()
        ? bank.accountHolderName()
        : "Tài khoản " + id;
  }

  private static String text(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String description(PaymentLineItem line) {
    var calculation = text(line.getCalculationDescription(), "");
    var note = text(line.getNote(), "");
    if (calculation.isEmpty()) return note.isEmpty() ? null : note;
    return note.isEmpty() || note.equals(calculation) ? calculation : calculation + " · " + note;
  }

  private AppException missing() {
    return new AppException(
        PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND, "Không tìm thấy chứng từ thanh toán.");
  }

  private void party(PaymentReceipt r, String actor) {
    if (actor == null || !actor.equals(r.getLandlordId()) && !actor.equals(r.getTenantId()))
      throw missing();
  }

  @Transactional(readOnly = true)
  public PaymentReceiptView get(String id, String actor) {
    var r = receipts.findById(id).orElseThrow(this::missing);
    party(r, actor);
    return view(r);
  }

  @Transactional(readOnly = true)
  public PaymentReceiptView byPayment(String id, String actor) {
    var r = receipts.findByPaymentRequestId(id).orElseThrow(this::missing);
    party(r, actor);
    return view(r);
  }

  @Transactional(readOnly = true)
  public PageResponse<PaymentReceiptView> list(
      String actor, String role, String contractId, String rentalId, int page, int size) {
    Specification<PaymentReceipt> filter =
        (r, q, cb) -> {
          var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
          predicates.add(
              "LANDLORD".equals(role)
                  ? cb.equal(r.get("landlordId"), actor)
                  : "TENANT".equals(role)
                      ? cb.equal(r.get("tenantId"), actor)
                      : cb.or(
                          cb.equal(r.get("landlordId"), actor),
                          cb.equal(r.get("tenantId"), actor)));
          if (contractId != null || rentalId != null) {
            var source = q.subquery(String.class);
            var p = source.from(PaymentRequest.class);
            var sourceFilters = new ArrayList<jakarta.persistence.criteria.Predicate>();
            sourceFilters.add(cb.equal(p.get("id"), r.get("paymentRequestId")));
            if (contractId != null) sourceFilters.add(cb.equal(p.get("contractId"), contractId));
            if (rentalId != null) sourceFilters.add(cb.equal(p.get("rentalRequestId"), rentalId));
            source
                .select(p.get("id"))
                .where(sourceFilters.toArray(jakarta.persistence.criteria.Predicate[]::new));
            predicates.add(cb.exists(source));
          }
          return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    var pageable =
        PageRequest.of(
            Math.max(0, page - 1),
            Math.min(100, Math.max(1, size)),
            Sort.by(Sort.Order.desc("issuedAt"), Sort.Order.desc("id")));
    return new PageResponse<>(receipts.findAll(filter, pageable).map(this::view));
  }

  @Transactional
  public PaymentReceiptView retry(String id, String actor) {
    var r = receipts.lock(id).orElseThrow(this::missing);
    party(r, actor);
    if (r.getPdfStatus() == PaymentReceipt.PdfStatus.FAILED
        && (r.getNextAttemptAt() == null || !Instant.now().isBefore(r.getNextAttemptAt()))) {
      r.setPdfStatus(PaymentReceipt.PdfStatus.PENDING);
      r.setAttempts(0);
      r.setNextAttemptAt(Instant.now());
      receipts.save(r);
    }
    return view(r);
  }

  @Transactional(readOnly = true)
  public byte[] pdf(String id, String actor) {
    var r = receipts.findById(id).orElseThrow(this::missing);
    party(r, actor);
    snapshot(r);
    if (r.getPdfStatus() != PaymentReceipt.PdfStatus.READY || r.getStorageObjectId() == null)
      throw new AppException(
          PaymentErrorCode.INVALID_PAYMENT_STATUS, "PDF đang được chuẩn bị. Vui lòng thử lại sau.");
    var file = objects.findById(r.getStorageObjectId()).orElseThrow(this::missing);
    if (file.getPurpose() != StoragePurpose.PAYMENT_RECEIPT
        || file.getVisibility() != StorageVisibility.PRIVATE
        || !"PAYMENT_RECEIPT".equals(file.getReferenceType())
        || !r.getId().equals(file.getReferenceId())) throw missing();
    return storage.downloadDirect(file.getId());
  }

  private PaymentReceiptView view(PaymentReceipt r) {
    var s = snapshot(r);
    boolean refund = s.direction() == PaymentDirection.LANDLORD_TO_TENANT;
    return new PaymentReceiptView(
        r.getId(),
        r.getPaymentRequestId(),
        r.getReceiptNumber(),
        refund ? "Phiếu xác nhận hoàn tiền" : "Phiếu xác nhận thanh toán",
        refund ? "REFUND" : "PAYMENT",
        s.total(),
        s.currency(),
        r.getIssuedAt(),
        s.payer().name(),
        s.payee().name(),
        s.context().contractNumber(),
        s.context().propertyAddress(),
        s.context().periodLabel(),
        s.transferReference(),
        r.getPdfStatus(),
        r.getSnapshotHash());
  }
}
