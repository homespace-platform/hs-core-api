package com.hs.payment.receipt;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(
    name = "payment_receipts",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_receipt_payment", columnNames = "payment_request_id"),
      @UniqueConstraint(name = "uq_receipt_number", columnNames = "receipt_number")
    },
    indexes = @Index(name = "idx_receipt_pdf_queue", columnList = "pdf_status,next_attempt_at"))
@Getter
@Setter
@NoArgsConstructor
public class PaymentReceipt extends BaseEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "payment_request_id", nullable = false, length = 36, updatable = false)
  private String paymentRequestId;

  @Column(name = "receipt_number", nullable = false, length = 60, updatable = false)
  private String receiptNumber;

  @Column(nullable = false, length = 36, updatable = false)
  private String landlordId;

  @Column(nullable = false, length = 36, updatable = false)
  private String tenantId;

  @Column(nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
  private String snapshotJson;

  @Column(nullable = false, length = 64, updatable = false)
  private String snapshotHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "pdf_status", nullable = false, length = 16)
  private PdfStatus pdfStatus = PdfStatus.PENDING;

  @Column(length = 36)
  private String storageObjectId;

  private Instant pdfCreatedAt;
  private int attempts;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Version private long version;

  public enum PdfStatus {
    PENDING,
    READY,
    FAILED
  }
}
