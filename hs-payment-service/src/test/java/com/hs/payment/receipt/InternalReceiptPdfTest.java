package com.hs.payment.receipt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.document.InternalDocumentError;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.membership.MembershipOverpayment;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import com.hs.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

/** Notification-only PDF access: owner/party only, ready only, snapshot integrity enforced. */
class InternalReceiptPdfTest {
  static final byte[] PDF = "%PDF-test".getBytes();
  final PaymentReceiptRepository rentalRepo = mock(PaymentReceiptRepository.class);
  final MembershipReceiptRepository memberRepo = mock(MembershipReceiptRepository.class);
  final StorageService storage = mock(StorageService.class);
  final StorageObjectRepository objects = mock(StorageObjectRepository.class);
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final PaymentReceiptService rental =
      new PaymentReceiptService(
          rentalRepo,
          mock(com.hs.payment.repository.PaymentLineItemRepository.class),
          mock(UserRepository.class),
          mapper,
          storage,
          objects);
  final MembershipReceiptService membership =
      new MembershipReceiptService(
          memberRepo,
          mock(com.hs.payment.membership.MembershipOrderRepository.class),
          mock(com.hs.payment.membership.MembershipOverpaymentRepository.class),
          mock(UserRepository.class),
          mapper,
          storage,
          objects);

  PaymentReceipt rentalReceipt;
  MembershipReceipt orderReceipt;
  MembershipReceipt overReceipt;

  @BeforeEach
  void setup() {
    var p =
        PaymentRequest.builder()
            .id("pay")
            .payerId("tenant")
            .payeeId("owner")
            .type(PaymentType.INITIAL)
            .direction(PaymentDirection.TENANT_TO_LANDLORD)
            .status(PaymentStatus.CONFIRMED)
            .totalAmount(new BigDecimal("3000000"))
            .confirmedAt(Instant.parse("2026-11-07T00:30:00Z"))
            .build();
    var saved = new ArrayList<PaymentReceipt>();
    when(rentalRepo.findByPaymentRequestId(anyString())).thenReturn(Optional.empty());
    when(rentalRepo.save(any()))
        .thenAnswer(
            c -> {
              saved.add(c.getArgument(0));
              return c.getArgument(0);
            });
    rental.capture(p);
    rentalReceipt = saved.get(0);
    rentalReceipt.setPdfStatus(PaymentReceipt.PdfStatus.READY);
    rentalReceipt.setStorageObjectId("s-rental");
    when(rentalRepo.findByPaymentRequestId("pay")).thenReturn(Optional.of(rentalReceipt));
    when(rentalRepo.findById(rentalReceipt.getId())).thenReturn(Optional.of(rentalReceipt));
    when(objects.findById("s-rental"))
        .thenReturn(Optional.of(file("s-rental", rentalReceipt.getId(), "PAYMENT_RECEIPT")));

    var o = new MembershipOrder();
    o.setId("o".repeat(32));
    o.setOwnerId("buyer");
    o.setTier(com.hs.listing.membership.MembershipTier.STARTER);
    o.setKind(com.hs.listing.membership.MembershipKind.PLAN);
    o.setAmountVnd(49_000);
    o.setStatus(MembershipOrder.Status.PAID);
    o.setProvider(MembershipOrder.Provider.ETH);
    o.setPaidAt(Instant.parse("2026-11-28T10:00:00Z"));
    var op = new MembershipOverpayment();
    op.setId("p".repeat(32));
    op.setOrderId(o.getId());
    op.setOwnerId("buyer");
    op.setAmountVnd(49_000);
    op.setProvider(MembershipOrder.Provider.ETH);
    op.setReceivedAt(Instant.parse("2026-11-28T11:00:00Z"));
    var memberSaved = new ArrayList<MembershipReceipt>();
    when(memberRepo.findByOrderId(anyString())).thenReturn(Optional.empty());
    when(memberRepo.save(any()))
        .thenAnswer(
            c -> {
              memberSaved.add(c.getArgument(0));
              return c.getArgument(0);
            });
    membership.capture(o);
    membership.captureOverpayment(op);
    orderReceipt = memberSaved.get(0);
    overReceipt = memberSaved.get(1);
    for (var r : List.of(orderReceipt, overReceipt)) {
      r.setPdfStatus(PaymentReceipt.PdfStatus.READY);
      r.setStorageObjectId("s-" + r.getOrderId().charAt(0));
      when(objects.findById(r.getStorageObjectId()))
          .thenReturn(
              Optional.of(
                  file(r.getStorageObjectId(), r.getId(), MembershipReceiptStorageAccess.REFERENCE_TYPE)));
      when(memberRepo.findByOrderId(r.getOrderId())).thenReturn(Optional.of(r));
    }
    when(storage.downloadDirect(anyString())).thenReturn(PDF);
  }

  static StorageObject file(String id, String referenceId, String type) {
    return StorageObject.builder()
        .id(id)
        .purpose(StoragePurpose.PAYMENT_RECEIPT)
        .visibility(StorageVisibility.PRIVATE)
        .referenceType(type)
        .referenceId(referenceId)
        .build();
  }

  static InternalDocumentError code(AppException e) {
    return Arrays.stream(InternalDocumentError.values())
        .filter(c -> c.getCode() == e.getCode())
        .findFirst()
        .orElseThrow();
  }

  @Test
  void rentalReceiptByPaymentIdOrReceiptIdForBothParties() {
    for (String who : new String[] {"tenant", "owner"}) {
      var f = rental.pdfForRecipient("pay", who);
      assertArrayEquals(PDF, f.bytes());
      assertEquals(rentalReceipt.getReceiptNumber() + ".pdf", f.fileName());
    }
    assertArrayEquals(PDF, rental.pdfForRecipient(rentalReceipt.getId(), "tenant").bytes());
  }

  @Test
  void rentalReceiptDeniedNotFoundNotReadyAndTampered() {
    assertEquals(
        InternalDocumentError.RECIPIENT_FORBIDDEN,
        code(assertThrows(AppException.class, () -> rental.pdfForRecipient("pay", "intruder"))));
    assertEquals(
        InternalDocumentError.RECIPIENT_FORBIDDEN,
        code(assertThrows(AppException.class, () -> rental.pdfForRecipient("pay", null))));
    assertEquals(
        InternalDocumentError.NOT_FOUND,
        code(assertThrows(AppException.class, () -> rental.pdfForRecipient("nope", "tenant"))));
    verify(storage, never()).downloadDirect(anyString());
    rentalReceipt.setPdfStatus(PaymentReceipt.PdfStatus.PENDING);
    assertEquals(
        InternalDocumentError.NOT_READY,
        code(assertThrows(AppException.class, () -> rental.pdfForRecipient("pay", "tenant"))));
    rentalReceipt.setPdfStatus(PaymentReceipt.PdfStatus.READY);
    rentalReceipt.setSnapshotJson(rentalReceipt.getSnapshotJson().replace("3000000", "1"));
    assertEquals(
        InternalDocumentError.INTEGRITY_FAILED,
        code(assertThrows(AppException.class, () -> rental.pdfForRecipient("pay", "tenant"))));
  }

  @Test
  void membershipAndOverpaymentReceiptsForTheBuyerOnly() {
    assertArrayEquals(PDF, membership.pdfForRecipient(orderReceipt.getOrderId(), "buyer", false).bytes());
    assertArrayEquals(PDF, membership.pdfForRecipient(overReceipt.getOrderId(), "buyer", true).bytes());
    assertEquals(
        InternalDocumentError.RECIPIENT_FORBIDDEN,
        code(assertThrows(AppException.class, () -> membership.pdfForRecipient(orderReceipt.getOrderId(), "owner", false))));
    assertEquals(
        InternalDocumentError.RECIPIENT_FORBIDDEN,
        code(assertThrows(AppException.class, () -> membership.pdfForRecipient(overReceipt.getOrderId(), "intruder", true))));
    // an order id is not an overpayment id and vice versa
    assertEquals(
        InternalDocumentError.NOT_FOUND,
        code(assertThrows(AppException.class, () -> membership.pdfForRecipient(orderReceipt.getOrderId(), "buyer", true))));
    assertEquals(
        InternalDocumentError.NOT_FOUND,
        code(assertThrows(AppException.class, () -> membership.pdfForRecipient(overReceipt.getOrderId(), "buyer", false))));
    assertEquals(
        InternalDocumentError.NOT_FOUND,
        code(assertThrows(AppException.class, () -> membership.pdfForRecipient("missing", "buyer", false))));
  }

  @Test
  void membershipNotReadyAndTampered() {
    orderReceipt.setPdfStatus(PaymentReceipt.PdfStatus.PENDING);
    assertEquals(
        InternalDocumentError.NOT_READY,
        code(assertThrows(AppException.class, () -> membership.pdfForRecipient(orderReceipt.getOrderId(), "buyer", false))));
    orderReceipt.setPdfStatus(PaymentReceipt.PdfStatus.READY);
    orderReceipt.setSnapshotJson(orderReceipt.getSnapshotJson().replace("49000", "1"));
    assertEquals(
        InternalDocumentError.INTEGRITY_FAILED,
        code(assertThrows(AppException.class, () -> membership.pdfForRecipient(orderReceipt.getOrderId(), "buyer", false))));
  }
}
