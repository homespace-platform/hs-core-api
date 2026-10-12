package com.hs.contract.dto.billing;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable audit entry. baseDueAt identifies the payment window, not a new bill. */
public record PaymentExtension(String id, int days, String note, Status status,
                               Instant requestedAt, Instant respondBy, String requestedBy,
                               Instant baseDueAt, BigDecimal frozenLateFee,
                               Instant decidedAt, String decidedBy, Instant newDueAt) {
    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED, CLOSED }
    public PaymentExtension decide(Status next, Instant at, String actor, Instant due) {
        return new PaymentExtension(id, days, note, next, requestedAt, respondBy, requestedBy,
                baseDueAt, frozenLateFee, at, actor, due);
    }
}
