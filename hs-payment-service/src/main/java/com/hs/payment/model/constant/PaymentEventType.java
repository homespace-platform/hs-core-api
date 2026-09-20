package com.hs.payment.model.constant;

public enum PaymentEventType {
    CREATED,
    TRANSFER_REPORTED,
    RECEIPT_CONFIRMED,
    RECEIPT_REJECTED,
    DISPUTED,
    EXPIRED,
    CANCELLED,
    REFUND_OBLIGATION_CREATED
}
