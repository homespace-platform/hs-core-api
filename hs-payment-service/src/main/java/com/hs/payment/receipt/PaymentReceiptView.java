package com.hs.payment.receipt;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentReceiptView(
    String id,
    String paymentId,
    String receiptNumber,
    String title,
    String kind,
    BigDecimal totalAmount,
    String currency,
    Instant issuedAt,
    String payerName,
    String payeeName,
    String contractNumber,
    String propertyAddress,
    String periodLabel,
    String transferReference,
    PaymentReceipt.PdfStatus pdfStatus,
    String snapshotHash) {}
