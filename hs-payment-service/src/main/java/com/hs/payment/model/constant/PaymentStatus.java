package com.hs.payment.model.constant;

public enum PaymentStatus {
    AWAITING_TRANSFER,
    TRANSFER_REPORTED,
    CONFIRMED,
    REJECTED,
    OVERDUE,
    EXPIRED,
    CANCELLED,
    @Deprecated // legacy rows only; no code path sets them (startup normalizer moves them out)
    DISPUTED;

    public boolean isConfirmed() {
        return this == CONFIRMED;
    }

    public boolean isFinalState() {
        return this == CONFIRMED || this == EXPIRED || this == CANCELLED;
    }
}
