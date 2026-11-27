package com.hs.payment.receipt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.model.StorageObject;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MembershipReceiptPdfWorkerTest {
  final MembershipReceiptRepository repo = mock(MembershipReceiptRepository.class);
  final MembershipReceiptService service = mock(MembershipReceiptService.class);
  final PaymentReceiptPdfRenderer renderer = mock(PaymentReceiptPdfRenderer.class);
  final StorageService storage = mock(StorageService.class);
  final StorageObjectRepository objects = mock(StorageObjectRepository.class);
  final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
  final MembershipReceiptPdfWorker worker =
      new MembershipReceiptPdfWorker(repo, service, renderer, storage, objects, tx);

  @Test
  void failureMarksFailedWithBackoffAndLaterSuccessMarksReady() {
    var r = new MembershipReceipt();
    r.setId("r");
    r.setReceiptNumber("HS-MB-20261128-AAAA");
    r.setSnapshotHash("h");
    r.setNextAttemptAt(Instant.now().minusSeconds(1));
    when(tx.getTransaction(any())).thenAnswer(c -> new SimpleTransactionStatus());
    when(repo.lock("r")).thenReturn(Optional.of(r));
    when(renderer.renderMembership(any(MembershipReceiptSnapshot.class), eq("h")))
        .thenThrow(new IllegalStateException("converter down"));
    worker.generate("r");
    assertEquals(PaymentReceipt.PdfStatus.FAILED, r.getPdfStatus());
    assertEquals(1, r.getAttempts());
    assertTrue(r.getNextAttemptAt().isAfter(Instant.now()));

    r.setNextAttemptAt(Instant.now().minusSeconds(1));
    when(renderer.renderMembership(any(MembershipReceiptSnapshot.class), eq("h")))
        .thenReturn("%PDF-1".getBytes());
    var file = mock(StorageObjectResponse.class);
    when(file.id()).thenReturn("file");
    when(storage.uploadDirect(
            any(), anyString(), anyString(), any(), eq("MEMBERSHIP_RECEIPT"), eq("r"), any(), anyString()))
        .thenReturn(file);
    var object = mock(StorageObject.class);
    when(object.getObjectKey()).thenReturn("k");
    when(objects.findById("file")).thenReturn(Optional.of(object));
    TransactionSynchronizationManager.initSynchronization();
    try {
      worker.generate("r");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertEquals(PaymentReceipt.PdfStatus.READY, r.getPdfStatus());
    assertEquals("file", r.getStorageObjectId());
  }
}
