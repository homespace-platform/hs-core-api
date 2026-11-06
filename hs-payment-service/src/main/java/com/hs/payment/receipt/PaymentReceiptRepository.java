package com.hs.payment.receipt;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentReceiptRepository
    extends JpaRepository<PaymentReceipt, String>, JpaSpecificationExecutor<PaymentReceipt> {
  Optional<PaymentReceipt> findByPaymentRequestId(String paymentId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from PaymentReceipt r where r.id=:id")
  Optional<PaymentReceipt> lock(@Param("id") String id);

  @Query(
      "select r.id from PaymentReceipt r where r.pdfStatus in :statuses and r.attempts<5 and"
          + " r.nextAttemptAt<=:now order by r.nextAttemptAt,r.id")
  List<String> pending(
      @Param("statuses") Collection<PaymentReceipt.PdfStatus> statuses,
      @Param("now") Instant now,
      Pageable pageable);

  boolean existsByStorageObjectId(String storageId);
}
