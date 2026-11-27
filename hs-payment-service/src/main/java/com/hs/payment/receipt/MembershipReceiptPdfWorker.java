package com.hs.payment.receipt;

import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Same lease/retry model as PaymentReceiptPdfWorker: PDF failures never touch the paid order. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipReceiptPdfWorker {
  private final MembershipReceiptRepository receipts;
  private final MembershipReceiptService service;
  private final PaymentReceiptPdfRenderer renderer;
  private final StorageService storage;
  private final StorageObjectRepository objects;
  private final PlatformTransactionManager transactions;
  private final AtomicBoolean running = new AtomicBoolean();
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon().name("membership-receipt-pdf-", 0).factory());

  @Scheduled(fixedDelayString = "${payment.receipts.pdf-check-delay-ms:15000}")
  public void tick() {
    if (!running.compareAndSet(false, true)) return;
    try {
      executor.submit(
          () -> {
            try {
              drain();
            } finally {
              running.set(false);
            }
          });
    } catch (RejectedExecutionException e) {
      running.set(false);
    }
  }

  private void drain() {
    try {
      for (var id :
          receipts.pending(
              List.of(PaymentReceipt.PdfStatus.PENDING, PaymentReceipt.PdfStatus.FAILED),
              Instant.now(),
              PageRequest.of(0, 12)))
        try {
          generate(id);
        } catch (Exception e) {
          log.error("MEMBERSHIP_RECEIPT_PDF_FAILED receipt={}", id, e);
        }
    } catch (Exception e) {
      log.error("MEMBERSHIP_RECEIPT_QUEUE_FAILED", e);
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }

  public void generate(String id) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              var r = receipts.lock(id).orElse(null);
              if (r == null
                  || r.getPdfStatus() == PaymentReceipt.PdfStatus.READY
                  || r.getAttempts() >= 5
                  || r.getNextAttemptAt() != null && Instant.now().isBefore(r.getNextAttemptAt()))
                return;
              r.setAttempts(r.getAttempts() + 1);
              try {
                byte[] pdf = renderer.renderMembership(service.snapshot(r), r.getSnapshotHash());
                var file =
                    storage.uploadDirect(
                        pdf,
                        r.getReceiptNumber() + ".pdf",
                        "application/pdf",
                        StoragePurpose.PAYMENT_RECEIPT,
                        MembershipReceiptStorageAccess.REFERENCE_TYPE,
                        r.getId(),
                        StorageVisibility.PRIVATE,
                        "SYSTEM");
                String key = objects.findById(file.id()).orElseThrow().getObjectKey();
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED)
                          try {
                            storage.deleteS3ObjectDirect(key);
                          } catch (Exception cleanup) {
                            log.error("RECEIPT_ORPHAN_CLEANUP_FAILED receipt={}", id, cleanup);
                          }
                      }
                    });
                r.setStorageObjectId(file.id());
                r.setPdfStatus(PaymentReceipt.PdfStatus.READY);
                r.setPdfCreatedAt(Instant.now());
                r.setNextAttemptAt(null);
              } catch (Exception e) {
                r.setPdfStatus(PaymentReceipt.PdfStatus.FAILED);
                r.setNextAttemptAt(Instant.now().plusSeconds(30L * r.getAttempts()));
                log.warn("MEMBERSHIP_RECEIPT_PDF_RETRY receipt={} attempt={}", id, r.getAttempts(), e);
              }
              receipts.save(r);
            });
  }
}
