package com.hs.payment.receipt;

import com.hs.payment.dto.BankAccountSnapshotDto;
import com.hs.payment.model.constant.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PaymentReceiptSnapshot(
    int schemaVersion,
    String receiptNumber,
    String paymentId,
    String rentalId,
    PaymentType paymentType,
    PaymentDirection direction,
    BigDecimal total,
    String currency,
    Instant confirmedAt,
    Instant reportedAt,
    String transferReference,
    String bankTransactionReference,
    Party payer,
    Party payee,
    Context context,
    List<Line> lines) {
  public record Party(String userId, String name, BankAccountSnapshotDto bank) {}

  public record Line(
      String name, BigDecimal amount, Integer quantity, BigDecimal unitPrice, String description) {}

  public record Settlement(BigDecimal depositAmount, List<Deduction> deductions) {}
  public record Deduction(String description, BigDecimal amount) {}

  public record Context(
      String contractNumber,
      String propertyAddress,
      String unitLabel,
      String periodLabel,
      String landlordName,
      String tenantName,
      String amountWords,
      Settlement settlement) {
    public Context(String contractNumber, String propertyAddress, String unitLabel, String periodLabel,
        String landlordName, String tenantName, String amountWords) {
      this(contractNumber, propertyAddress, unitLabel, periodLabel, landlordName, tenantName, amountWords, null);
    }
    public static Context empty() {
      return new Context(null, null, null, null, null, null, null);
    }
  }
}
