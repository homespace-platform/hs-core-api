package com.hs.payment.receipt;

import java.time.Instant;

public record MembershipReceiptView(
    String id,
    String orderId,
    String receiptNumber,
    String itemName,
    String kind,
    String tier,
    int quantity,
    int periodDays,
    long totalAmount,
    String currency,
    String provider,
    String transactionRef,
    Instant issuedAt,
    PaymentReceipt.PdfStatus pdfStatus,
    String note,
    /** PURCHASE or OVERPAYMENT. */
    String type,
    /** Null for PURCHASE; REFUND_PENDING or REFUNDED for OVERPAYMENT. */
    String refundStatus) {}
