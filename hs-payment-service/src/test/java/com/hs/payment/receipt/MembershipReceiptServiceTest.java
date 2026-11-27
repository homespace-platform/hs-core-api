package com.hs.payment.receipt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.listing.membership.MembershipKind;
import com.hs.listing.membership.MembershipTier;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.membership.MembershipOrderRepository;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import com.hs.user.repository.UserRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MembershipReceiptServiceTest {
  final MembershipReceiptRepository repo = mock(MembershipReceiptRepository.class);
  final UserRepository users = mock(UserRepository.class);
  final com.hs.payment.membership.MembershipOverpaymentRepository overpayments =
      mock(com.hs.payment.membership.MembershipOverpaymentRepository.class);
  final MembershipReceiptService service =
      new MembershipReceiptService(
          repo,
          mock(MembershipOrderRepository.class),
          overpayments,
          users,
          new ObjectMapper().findAndRegisterModules(),
          mock(StorageService.class),
          mock(StorageObjectRepository.class));

  MembershipOrder paid() {
    var o = new MembershipOrder();
    o.setId("a".repeat(32));
    o.setOwnerId("owner");
    o.setTier(MembershipTier.STARTER);
    o.setKind(MembershipKind.PLAN);
    o.setAmountVnd(49_000);
    o.setStatus(MembershipOrder.Status.PAID);
    o.setProvider(MembershipOrder.Provider.ETH);
    o.setTransactionNo("f".repeat(60) + "9abc");
    o.setPaidAt(Instant.parse("2026-11-28T17:30:00Z")); // 29/11 in Vietnam
    return o;
  }

  MembershipReceipt capture(MembershipOrder o) {
    var saved = new ArrayList<MembershipReceipt>();
    when(repo.save(any()))
        .thenAnswer(
            c -> {
              saved.add(c.getArgument(0));
              return c.getArgument(0);
            });
    service.capture(o);
    return saved.isEmpty() ? null : saved.get(0);
  }

  @Test
  void capturesOncePerPaidOrderWithFormatMaskAndPaidAtIssueDate() {
    var o = paid();
    var r = capture(o);
    assertTrue(r.getReceiptNumber().matches("HS-MB-20261129-[0-9A-F]{16}"), r.getReceiptNumber());
    assertEquals(o.getPaidAt(), r.getIssuedAt());
    assertEquals(PaymentReceipt.PdfStatus.PENDING, r.getPdfStatus());
    assertTrue(r.getSnapshotJson().contains("****9abc"));
    assertFalse(r.getSnapshotJson().contains("ffff"));
    assertEquals(PaymentReceiptService.hash(r.getSnapshotJson()), r.getSnapshotHash());
    when(repo.findByOrderId(o.getId())).thenReturn(Optional.of(r));
    service.capture(o);
    verify(repo, times(1)).save(any());
  }

  @Test
  void numbersAreUniqueAcrossOrders() {
    assertNotEquals(capture(paid()).getReceiptNumber(), capture(paid()).getReceiptNumber());
  }

  @Test
  void reviewOrUnpaidOrdersGetNoReceipt() {
    var o = paid();
    o.setStatus(MembershipOrder.Status.REVIEW);
    service.capture(o);
    o.setStatus(MembershipOrder.Status.PAID);
    o.setPaidAt(null);
    service.capture(o);
    verify(repo, never()).save(any());
  }

  @Test
  void upgradeAndAddonLinesAreDescribed() {
    var up = paid();
    up.setUpgrade(true);
    up.setTier(MembershipTier.PRO);
    assertTrue(capture(up).getSnapshotJson().contains("Nâng cấp lên gói Pro"));
    var addon = paid();
    addon.setKind(MembershipKind.ADDON);
    addon.setTier(null);
    addon.setQuantity(3);
    var r = capture(addon);
    assertTrue(r.getSnapshotJson().contains("Mua thêm 3 lượt đăng tin"));
    assertTrue(r.getSnapshotJson().contains("\"periodDays\":90"));
  }

  @Test
  void onlyTheBuyerCanReadOrDownload() {
    var r = capture(paid());
    r.setId("r1");
    when(repo.findById("r1")).thenReturn(Optional.of(r));
    when(repo.lock("r1")).thenReturn(Optional.of(r));
    when(repo.findByOrderId(r.getOrderId())).thenReturn(Optional.of(r));
    assertEquals(r.getReceiptNumber(), service.get("r1", "owner").receiptNumber());
    for (String other : new String[] {"intruder", null}) {
      assertThrows(AppException.class, () -> service.get("r1", other));
      assertThrows(AppException.class, () -> service.byOrder(r.getOrderId(), other));
      assertThrows(AppException.class, () -> service.pdf("r1", other));
      assertThrows(AppException.class, () -> service.retry("r1", other));
    }
  }

  @Test
  void snapshotTamperingIsDetected() {
    var r = capture(paid());
    r.setSnapshotJson(r.getSnapshotJson().replace("49000", "1"));
    assertThrows(IllegalStateException.class, () -> service.snapshot(r));
  }

  @Test
  void renderedHtmlShowsMaskedReferenceAndAmount() {
    var r = capture(paid());
    var html =
        new PaymentReceiptPdfRenderer("http://x", false)
            .membershipHtml(service.snapshot(r), r.getSnapshotHash());
    assertTrue(html.contains("Gói hội viên Starter"));
    assertTrue(html.contains("****9abc"));
    assertTrue(html.contains("49.000"));
  }

  @Test
  void overpaymentReceiptSaysItWillBeRefundedAndIsIdempotent() {
    var p = new com.hs.payment.membership.MembershipOverpayment();
    p.setId("p".repeat(32));
    p.setOrderId("a".repeat(32));
    p.setOwnerId("owner");
    p.setProvider(MembershipOrder.Provider.SEPAY);
    p.setTransactionNo("tx-second-9999");
    p.setAmountVnd(49_000);
    p.setReceivedAt(Instant.parse("2026-11-28T10:00:00Z"));
    var saved = new ArrayList<MembershipReceipt>();
    when(repo.save(any())).thenAnswer(c -> { saved.add(c.getArgument(0)); return c.getArgument(0); });
    service.captureOverpayment(p);
    assertEquals(1, saved.size());
    assertEquals(p.getId(), saved.get(0).getOrderId());
    assertTrue(saved.get(0).getSnapshotJson().contains("sẽ được hoàn lại"));
    assertTrue(saved.get(0).getSnapshotJson().contains("****9999"));
    when(repo.findByOrderId(p.getId())).thenReturn(Optional.of(saved.get(0)));
    service.captureOverpayment(p);
    verify(repo, times(1)).save(any());
  }

  @Test
  void viewExposesTypeAndBatchedRefundStatus() {
    var purchase = capture(paid());
    var pay = new com.hs.payment.membership.MembershipOverpayment();
    pay.setId("p".repeat(32)); pay.setOrderId("a".repeat(32)); pay.setOwnerId("owner");
    pay.setProvider(MembershipOrder.Provider.SEPAY); pay.setTransactionNo("tx9999");
    pay.setAmountVnd(49_000); pay.setReceivedAt(Instant.parse("2026-11-28T10:00:00Z"));
    var saved = new ArrayList<MembershipReceipt>();
    when(repo.save(any())).thenAnswer(c -> { saved.add(c.getArgument(0)); return c.getArgument(0); });
    service.captureOverpayment(pay);
    var refund = saved.get(0);
    pay.setStatus(com.hs.payment.membership.MembershipOverpayment.Status.REFUNDED);
    when(overpayments.findAllById(any())).thenReturn(List.of(pay));
    when(repo.findByOwnerId(eq("owner"), any()))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(purchase, refund)));
    var items = service.list("owner", 1, 10).getResult();
    assertEquals("PURCHASE", items.get(0).type());
    assertNull(items.get(0).refundStatus());
    assertEquals("OVERPAYMENT", items.get(1).type());
    assertEquals("REFUNDED", items.get(1).refundStatus());
    verify(overpayments, times(1)).findAllById(any());
  }
}
