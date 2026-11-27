package com.hs.payment.receipt;

import java.time.Instant;

/** Immutable receipt content. Payee is HomeSpace; no tax id is configured, so none is shown. */
public record MembershipReceiptSnapshot(
    int schemaVersion,
    String receiptNumber,
    String orderId,
    String buyerName,
    String payeeName,
    String itemName,
    String kind,
    String tier,
    int quantity,
    int periodDays,
    long amountVnd,
    String provider,
    String transactionRef,
    Instant paidAt,
    /** What was applied when the payment was converted to add-on uses; null for a normal purchase. */
    String note) {}
