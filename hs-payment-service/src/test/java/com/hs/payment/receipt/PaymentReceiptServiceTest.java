package com.hs.payment.receipt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentLineItemRepository;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import com.hs.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentReceiptServiceTest {
  PaymentReceiptRepository repo;
  PaymentLineItemRepository lines;
  PaymentReceiptService service;
  PaymentRequest source;
  List<PaymentReceipt> saved;

  @BeforeEach
  void setup() {
    repo = mock(PaymentReceiptRepository.class);
    lines = mock(PaymentLineItemRepository.class);
    service =
        new PaymentReceiptService(
            repo,
            lines,
            mock(UserRepository.class),
            new ObjectMapper(),
            mock(StorageService.class),
            mock(StorageObjectRepository.class));
    source =
        PaymentRequest.builder()
            .id("payment")
            .payerId("tenant")
            .payeeId("owner")
            .type(PaymentType.INITIAL)
            .direction(PaymentDirection.TENANT_TO_LANDLORD)
            .status(PaymentStatus.CONFIRMED)
            .totalAmount(new BigDecimal("3000000"))
            .confirmedAt(Instant.parse("2026-11-07T00:30:00Z"))
            .transferReference("HSPAYMENT")
            .build();
    saved = new ArrayList<>();
    lenient()
        .when(repo.findByPaymentRequestId(anyString()))
        .thenAnswer(
            c ->
                saved.stream()
                    .filter(r -> r.getPaymentRequestId().equals(c.getArgument(0)))
                    .findFirst());
    lenient()
        .when(repo.save(any()))
        .thenAnswer(
            c -> {
              PaymentReceipt r = c.getArgument(0);
              saved.add(r);
              return r;
            });
  }

  @ParameterizedTest
  @EnumSource(
      value = PaymentStatus.class,
      names = {"CONFIRMED"},
      mode = EnumSource.Mode.EXCLUDE)
  void unconfirmedMoneyHasNoReceipt(PaymentStatus state) {
    source.setStatus(state);
    service.capture(source);
    assertTrue(saved.isEmpty());
  }

  @Test
  void zeroOrMissingConfirmationNeverBecomesABankReceipt() {
    source.setTotalAmount(BigDecimal.ZERO);
    service.capture(source);
    source.setTotalAmount(BigDecimal.ONE);
    source.setConfirmedAt(null);
    service.capture(source);
    assertTrue(saved.isEmpty());
  }

  @Test
  void combinedPaymentFreezesExactAllocationsAndIsIdempotent() {
    when(lines.findByPaymentRequestIdOrderBySortOrderAsc("payment"))
        .thenReturn(List.of(row("Thuê kỳ đầu", "2000000"), row("Cọc", "1000000")));
    service.capture(source);
    service.capture(source);
    assertEquals(1, saved.size());
    var snapshot = service.snapshot(saved.getFirst());
    assertEquals(2, snapshot.lines().size());
    assertEquals(
        0,
        snapshot
            .total()
            .compareTo(
                snapshot.lines().stream()
                    .map(PaymentReceiptSnapshot.Line::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)));
    String original = saved.getFirst().getSnapshotJson();
    source.setTotalAmount(BigDecimal.TEN);
    service.capture(source);
    assertEquals(original, saved.getFirst().getSnapshotJson());
  }

  @Test
  void creditsArePreservedWithoutRecalculatingAnIssuedInvoice() {
    when(lines.findByPaymentRequestIdOrderBySortOrderAsc("payment"))
        .thenReturn(List.of(row("Thuê", "3500000"), row("Giảm trừ", "-500000")));
    service.capture(source);
    assertEquals(
        new BigDecimal("-500000"), service.snapshot(saved.getFirst()).lines().get(1).amount());
  }

  @Test
  void inconsistentLineItemsFallBackToTheConfirmedTotal() {
    when(lines.findByPaymentRequestIdOrderBySortOrderAsc("payment"))
        .thenReturn(List.of(row("Sai tổng", "1000000")));
    service.capture(source);
    assertEquals(
        source.getTotalAmount(), service.snapshot(saved.getFirst()).lines().getFirst().amount());
  }

  @Test
  void onlyExactParticipantsCanReadTheFrozenSnapshot() {
    service.capture(source);
    var r = saved.getFirst();
    when(repo.findById(r.getId())).thenReturn(Optional.of(r));
    assertEquals(r.getReceiptNumber(), service.get(r.getId(), "owner").receiptNumber());
    assertEquals(r.getReceiptNumber(), service.get(r.getId(), "tenant").receiptNumber());
    assertThrows(AppException.class, () -> service.get(r.getId(), "stranger"));
    assertThrows(AppException.class, () -> service.get(r.getId(), null));
  }

  @Test
  void changedSnapshotFailsIntegrityCheck() {
    service.capture(source);
    var r = saved.getFirst();
    r.setSnapshotJson(r.getSnapshotJson() + " ");
    assertThrows(IllegalStateException.class, () -> service.snapshot(r));
  }

  @Test
  void refundDirectionKeepsRolesAndOriginalReceipt() {
    service.capture(source);
    source.setId("refund");
    source.setPayerId("owner");
    source.setPayeeId("tenant");
    source.setDirection(PaymentDirection.LANDLORD_TO_TENANT);
    source.setType(PaymentType.INITIAL_PAYMENT_REFUND);
    service.capture(source);
    assertEquals(2, saved.size());
    var refund = saved.get(1);
    assertEquals("owner", refund.getLandlordId());
    assertEquals("tenant", refund.getTenantId());
    assertEquals("owner", service.snapshot(refund).payer().userId());
    assertTrue(refund.getReceiptNumber().startsWith("HS-HT-"));
  }

  private PaymentLineItem row(String label, String amount) {
    return PaymentLineItem.builder()
        .displayName(label)
        .quantity(1)
        .amount(new BigDecimal(amount))
        .build();
  }
}
