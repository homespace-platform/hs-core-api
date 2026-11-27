package com.hs.contract.dto.branch;

import com.hs.contract.model.MonthlyInvoiceStatus;

import java.time.Instant;
import java.time.LocalDate;

public record BranchInvoiceRow(String id, String contractId, int periodIndex, LocalDate periodEndExclusive,
                               MonthlyInvoiceStatus status, Instant dueAt, String resolutionMode,
                               String paymentRequestId, LocalDate periodStart, String costTermsRevisionId,
                               boolean finalCostSupplement, boolean draftPrepared) {

    public BranchInvoiceRow(String id, String contractId, int periodIndex, LocalDate periodEndExclusive,
                            MonthlyInvoiceStatus status, Instant dueAt, String resolutionMode,
                            String paymentRequestId, LocalDate periodStart, String costTermsRevisionId) {
        this(id, contractId, periodIndex, periodEndExclusive, status, dueAt, resolutionMode, paymentRequestId,
                periodStart, costTermsRevisionId, false, false);
    }
}
