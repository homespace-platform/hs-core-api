package com.hs.payment.receipt;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MembershipReceiptRepository extends JpaRepository<MembershipReceipt, String> {
  Optional<MembershipReceipt> findByOrderId(String orderId);

  Page<MembershipReceipt> findByOwnerId(String ownerId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from MembershipReceipt r where r.id=:id")
  Optional<MembershipReceipt> lock(@Param("id") String id);

  @Query(
      "select r.id from MembershipReceipt r where r.pdfStatus in :statuses and r.attempts<5 and"
          + " r.nextAttemptAt<=:now order by r.nextAttemptAt,r.id")
  List<String> pending(
      @Param("statuses") Collection<PaymentReceipt.PdfStatus> statuses,
      @Param("now") Instant now,
      Pageable pageable);

  /** PAID orders without a receipt; rows stay locked until the batch transaction ends. */
  @Query(
      value =
          "SELECT o.id FROM membership_orders o WHERE o.status='PAID' AND o.paid_at IS NOT NULL"
              + " AND NOT EXISTS (SELECT 1 FROM membership_receipts r WHERE r.order_id=o.id)"
              + " AND o.id > :after ORDER BY o.id LIMIT :size FOR UPDATE OF o SKIP LOCKED",
      nativeQuery = true)
  List<String> missingForPaidOrders(@Param("after") String after, @Param("size") int size);

  boolean existsByStorageObjectId(String storageId);
}
