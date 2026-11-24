package com.hs.payment.model.constant;

public enum PaymentEventType {
    CREATED,
    TRANSFER_REPORTED,
    RECEIPT_CONFIRMED,
    RECEIPT_REJECTED,
    @Deprecated // legacy rows only; no code path sets them
    DISPUTED,
    @Deprecated // legacy rows; only LegacyPaymentDisputeNormalizer writes it, when clearing old DISPUTED payments
    DISPUTE_ROUTED,
    EXPIRED,
    CANCELLED,
    REFUND_OBLIGATION_CREATED,
    OVERDUE,
    AMOUNT_ADJUSTED,
    DUE_DATE_EXTENDED
}
