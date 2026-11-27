package com.hs.payment.receipt;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** Frozen receipt for one PAID membership order. Separate from payment_receipts (rental money). */
@Entity
@Table(
    name = "membership_receipts",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_membership_receipt_order", columnNames = "order_id"),
      @UniqueConstraint(name = "uq_membership_receipt_number", columnNames = "receipt_number")
    },
    indexes = {
      @Index(name = "idx_membership_receipt_pdf_queue", columnList = "pdf_status,next_attempt_at"),
      @Index(name = "idx_membership_receipt_owner", columnList = "owner_id,issued_at")
    })
@Getter
@Setter
@NoArgsConstructor
public class MembershipReceipt extends BaseEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "order_id", nullable = false, length = 32, updatable = false)
  private String orderId;

  @Column(name = "owner_id", nullable = false, length = 36, updatable = false)
  private String ownerId;

  @Column(name = "receipt_number", nullable = false, length = 60, updatable = false)
  private String receiptNumber;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
  private String snapshotJson;

  @Column(nullable = false, length = 64, updatable = false)
  private String snapshotHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "pdf_status", nullable = false, length = 16)
  private PaymentReceipt.PdfStatus pdfStatus = PaymentReceipt.PdfStatus.PENDING;

  @Column(length = 36)
  private String storageObjectId;

  private Instant pdfCreatedAt;
  private int attempts;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Version private long version;
}
