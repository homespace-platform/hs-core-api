package com.hs.contract.dto.billing;

import java.time.Instant;
import java.time.LocalDate;

/** A recorded proposal or collection step, not a legal notice or termination decision. */
public record OverdueAction(String id, Type type, String note, LocalDate proposedDate,
                            Instant createdAt, String createdBy,
                            String tenantAcknowledgment, Instant acknowledgedAt) {
    public enum Type {
        PAYMENT_REQUEST, EXTENSION_PROPOSAL, MUTUAL_TERMINATION_PROPOSAL, LEGAL_REVIEW
    }
}
