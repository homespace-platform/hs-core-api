package com.hs.contract.dto.billing;

import java.time.Instant;

/** Persisted system decision. Tenant review is optional and never represents consent. */
@lombok.Builder(toBuilder = true)
public record UnilateralTerminationNotice(String id, String invoiceId, int version, Status status,
        String reason, Instant notifiedAt, Instant respondBy, Option requestedOption, Integer requestedDays,
        String requestReason, Instant requestedAt, Instant decidedAt, String decisionReason,
        Instant endedAt, Instant handoverAt) {
    public enum Status { NOTICE, REVIEW_REQUESTED, REPLACED, WITHDRAWN, CANCELLED_PAID, ENDED }
    public enum Option { ROLLOVER, PAYMENT_EXTENSION }
    public boolean pending() { return status == Status.NOTICE || status == Status.REVIEW_REQUESTED; }
}
