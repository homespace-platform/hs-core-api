package com.hs.payment.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.dto.PageResponse;
import com.hs.listing.membership.MembershipKind;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.membership.MembershipOrderRepository;
import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import com.hs.user.repository.UserRepository;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class MembershipReceiptService {
  private final MembershipReceiptRepository receipts;
  private final MembershipOrderRepository orders;
  private final UserRepository users;
  private final ObjectMapper mapper;
  private final StorageService storage;
  private final StorageObjectRepository objects;

  /** Local DB work inside the PAID transition. Idempotent per order (unique order_id is the net). */
  @Transactional(propagation = Propagation.MANDATORY)
  public void capture(MembershipOrder o) {
    if (o.getStatus() != MembershipOrder.Status.PAID || o.getPaidAt() == null) return;
    if (receipts.findByOrderId(o.getId()).isPresent()) return;
    var r = new MembershipReceipt();
    r.setId(UUID.randomUUID().toString());
    r.setOrderId(o.getId());
    r.setOwnerId(o.getOwnerId());
    r.setIssuedAt(o.getPaidAt());
    r.setReceiptNumber(
        "HS-MB-"
            + o.getPaidAt()
                .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            + "-"
            + r.getId().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT));
    boolean addon = o.getKind() == MembershipKind.ADDON;
    String tier = o.getTier() == null ? null : o.getTier().name();
    String label = tier == null ? "" : tier.charAt(0) + tier.substring(1).toLowerCase(Locale.ROOT);
    int quantity = addon && o.getQuantity() != null ? o.getQuantity() : 1;
    var snapshot =
        new MembershipReceiptSnapshot(
            1,
            r.getReceiptNumber(),
            o.getId(),
            buyerName(o.getOwnerId()),
            "HomeSpace",
            addon
                ? "Mua thêm " + quantity + " lượt đăng tin"
                : o.isUpgrade()
                    ? "Nâng cấp lên gói " + label + " (phần chênh lệch)"
                    : "Gói hội viên " + label,
            o.getKind().name(),
            tier,
            quantity,
            addon ? com.hs.listing.membership.MembershipService.ADDON_VALID_DAYS : com.hs.listing.membership.MembershipService.PERIOD_DAYS,
            o.getAmountVnd(),
            o.getProvider() == null ? null : o.getProvider().name(),
            mask(o.getTransactionNo()),
            o.getPaidAt(),
            null);
    try {
      r.setSnapshotJson(mapper.copy().findAndRegisterModules().writeValueAsString(snapshot));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot freeze membership receipt", e);
    }
    r.setSnapshotHash(PaymentReceiptService.hash(r.getSnapshotJson()));
    r.setNextAttemptAt(Instant.now());
    receipts.save(r);
  }

  /** Receipt for an overpayment (second payment): keyed by the overpayment id, says it will be refunded. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void captureOverpayment(com.hs.payment.membership.MembershipOverpayment p) {
    if (receipts.findByOrderId(p.getId()).isPresent()) return;
    var r = new MembershipReceipt();
    r.setId(UUID.randomUUID().toString());
    r.setOrderId(p.getId());
    r.setOwnerId(p.getOwnerId());
    r.setIssuedAt(p.getReceivedAt());
    r.setReceiptNumber(
        "HS-MB-"
            + p.getReceivedAt().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            + "-" + r.getId().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT));
    var snapshot =
        new MembershipReceiptSnapshot(
            1, r.getReceiptNumber(), p.getOrderId(), buyerName(p.getOwnerId()), "HomeSpace",
            "Khoản trả thừa – sẽ được hoàn lại", "OVERPAYMENT", null, 1, 0, p.getAmountVnd(),
            p.getProvider() == null ? null : p.getProvider().name(), mask(p.getTransactionNo()), p.getReceivedAt(),
            "Khoản trả thừa – sẽ được hoàn lại");
    try {
      r.setSnapshotJson(mapper.copy().findAndRegisterModules().writeValueAsString(snapshot));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot freeze membership receipt", e);
    }
    r.setSnapshotHash(PaymentReceiptService.hash(r.getSnapshotJson()));
    r.setNextAttemptAt(Instant.now());
    receipts.save(r);
  }

  /** One backfill batch: PAID orders lacking a receipt, rows locked SKIP LOCKED. Returns the ids seen. */
  @Transactional
  public List<String> backfillBatch(String after, int size) {
    var ids = receipts.missingForPaidOrders(after, size);
    for (var id : ids) orders.findById(id).ifPresent(this::capture);
    return ids;
  }

  static String mask(String ref) {
    if (ref == null || ref.isBlank()) return null;
    return "****" + (ref.length() <= 4 ? ref : ref.substring(ref.length() - 4));
  }

  private String buyerName(String ownerId) {
    var user = users.findById(ownerId).orElse(null);
    if (user != null) {
      String name =
          (Objects.toString(user.getFirstName(), "") + " " + Objects.toString(user.getLastName(), ""))
              .trim();
      if (!name.isBlank()) return name;
    }
    return "Chủ nhà";
  }

  MembershipReceiptSnapshot snapshot(MembershipReceipt r) {
    if (!PaymentReceiptService.hash(r.getSnapshotJson()).equals(r.getSnapshotHash()))
      throw new IllegalStateException("Receipt snapshot integrity check failed");
    try {
      return mapper
          .copy()
          .findAndRegisterModules()
          .readValue(r.getSnapshotJson(), MembershipReceiptSnapshot.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid receipt snapshot", e);
    }
  }

  private AppException missing() {
    return new AppException(
        PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND, "Không tìm thấy chứng từ thanh toán.");
  }

  /** Buyer only; any other user gets the same not-found as a missing receipt. */
  private MembershipReceipt owned(MembershipReceipt r, String actor) {
    if (actor == null || !actor.equals(r.getOwnerId())) throw missing();
    return r;
  }

  @Transactional(readOnly = true)
  public MembershipReceiptView get(String id, String actor) {
    return view(owned(receipts.findById(id).orElseThrow(this::missing), actor));
  }

  @Transactional(readOnly = true)
  public MembershipReceiptView byOrder(String orderId, String actor) {
    return view(owned(receipts.findByOrderId(orderId).orElseThrow(this::missing), actor));
  }

  @Transactional(readOnly = true)
  public PageResponse<MembershipReceiptView> list(String actor, int page, int size) {
    var pageable =
        PageRequest.of(
            Math.max(0, page - 1),
            Math.min(100, Math.max(1, size)),
            Sort.by(Sort.Order.desc("issuedAt"), Sort.Order.desc("id")));
    return new PageResponse<>(receipts.findByOwnerId(actor, pageable).map(this::view));
  }

  @Transactional
  public MembershipReceiptView retry(String id, String actor) {
    var r = owned(receipts.lock(id).orElseThrow(this::missing), actor);
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
    var r = owned(receipts.findById(id).orElseThrow(this::missing), actor);
    snapshot(r);
    if (r.getPdfStatus() != PaymentReceipt.PdfStatus.READY || r.getStorageObjectId() == null)
      throw new AppException(
          PaymentErrorCode.INVALID_PAYMENT_STATUS, "PDF đang được chuẩn bị. Vui lòng thử lại sau.");
    var file = objects.findById(r.getStorageObjectId()).orElseThrow(this::missing);
    if (file.getPurpose() != StoragePurpose.PAYMENT_RECEIPT
        || file.getVisibility() != StorageVisibility.PRIVATE
        || !MembershipReceiptStorageAccess.REFERENCE_TYPE.equals(file.getReferenceType())
        || !r.getId().equals(file.getReferenceId())) throw missing();
    return storage.downloadDirect(file.getId());
  }

  private MembershipReceiptView view(MembershipReceipt r) {
    var s = snapshot(r);
    return new MembershipReceiptView(
        r.getId(),
        r.getOrderId(),
        r.getReceiptNumber(),
        s.itemName(),
        s.kind(),
        s.tier(),
        s.quantity(),
        s.periodDays(),
        s.amountVnd(),
        "VND",
        s.provider(),
        s.transactionRef(),
        r.getIssuedAt(),
        r.getPdfStatus(),
        s.note());
  }
}
