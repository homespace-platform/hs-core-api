package com.hs.payment.model.constant;

public enum DepositStatus {
    PENDING,
    HELD,
    SETTLEMENT_PENDING,
    REFUND_DUE,
    REFUND_REPORTED,
    REFUNDED,
    DISPUTED, // still set by contract closure settlement disputes (not payment disputes)
    RETAINED_BY_LANDLORD
}
