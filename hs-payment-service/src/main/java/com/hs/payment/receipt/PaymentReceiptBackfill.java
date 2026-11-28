package com.hs.payment.receipt;

import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Startup job: receipts for CONFIRMED payments confirmed before receipts existed. Reuses
 * {@link PaymentReceiptService#capture} (same snapshot rules, {@code issuedAt} = original confirmation time) and
 * lets {@link PaymentReceiptPdfWorker} render the PDFs. Idempotent, one transaction per payment under the payment row
 * lock (the confirmation path's lock order); logs counts only.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReceiptBackfill {
  private static final int BATCH = 50;
  private final PaymentRequestRepository payments;
  private final PaymentReceiptRepository receipts;
  private final PaymentReceiptService service;
  private final PlatformTransactionManager transactions;

  @Value("${payment.receipts.backfill-enabled:true}")
  private boolean enabled;

  public record Result(int created, int failed) {}

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (enabled) Thread.ofPlatform().daemon().name("payment-receipt-backfill").start(this::run);
  }

  /** The id cursor guarantees progress even when a payment cannot be frozen. */
  public Result run() {
    var tx = new TransactionTemplate(transactions);
    int created = 0, failed = 0;
    try {
      String after = "";
      while (true) {
        var ids = payments.findConfirmedWithoutReceipt(after, PageRequest.of(0, BATCH));
        if (ids.isEmpty()) break;
        for (var id : ids) {
          try {
            if (Boolean.TRUE.equals(tx.execute(t -> capture(id)))) created++;
          } catch (Exception e) {
            failed++;
            log.error("PAYMENT_RECEIPT_BACKFILL_ITEM_FAILED payment={}", id, e);
          }
        }
        after = ids.get(ids.size() - 1);
      }
      log.info("PAYMENT_RECEIPT_BACKFILL_DONE created={} failed={}", created, failed);
    } catch (Exception e) {
      failed++;
      log.error("PAYMENT_RECEIPT_BACKFILL_FAILED created={} failed={}", created, failed, e);
    }
    return new Result(created, failed);
  }

  private boolean capture(String id) {
    var p = payments.findByIdForUpdate(id).orElse(null);
    if (p == null
        || p.getStatus() != PaymentStatus.CONFIRMED
        || receipts.findByPaymentRequestId(id).isPresent()) return false;
    service.capture(p);
    return receipts.findByPaymentRequestId(id).isPresent();
  }
}
