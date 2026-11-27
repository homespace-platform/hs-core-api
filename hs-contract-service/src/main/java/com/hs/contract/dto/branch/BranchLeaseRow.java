package com.hs.contract.dto.branch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.constant.ContractStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The columns that decide where a lease sits on the branch map; rental and closure columns may be null.
 * {@code awaitingHandover}: unilaterally ended lease whose room the landlord has not taken back yet.
 */
public record BranchLeaseRow(String id, String contractNumber, ContractStatus status,
                             LocalDate agreedEarlyEndExclusive, Integer agreedEarlyBillingMonths,
                             Integer effectiveLeaseMonths, LocalDate moveInDate, Integer leaseMonths,
                             String tenantName, Instant handedOverAt, boolean awaitingHandover) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public BranchLeaseRow(String id, String contractNumber, ContractStatus status,
                          LocalDate agreedEarlyEndExclusive, Integer agreedEarlyBillingMonths,
                          Integer effectiveLeaseMonths, LocalDate moveInDate, Integer leaseMonths,
                          String tenantName, Instant handedOverAt) {
        this(id, contractNumber, status, agreedEarlyEndExclusive, agreedEarlyBillingMonths, effectiveLeaseMonths,
                moveInDate, leaseMonths, tenantName, handedOverAt, false);
    }

    /** Query projection: derives the handover mark from the contract's termination columns. */
    public BranchLeaseRow(String id, String contractNumber, ContractStatus status,
                          LocalDate agreedEarlyEndExclusive, Integer agreedEarlyBillingMonths,
                          Integer effectiveLeaseMonths, LocalDate moveInDate, Integer leaseMonths,
                          String tenantName, Instant handedOverAt,
                          String terminationInvoiceId, String unilateralTerminationSnapshot) {
        this(id, contractNumber, status, agreedEarlyEndExclusive, agreedEarlyBillingMonths, effectiveLeaseMonths,
                moveInDate, leaseMonths, tenantName, handedOverAt,
                awaitingHandover(status, terminationInvoiceId, unilateralTerminationSnapshot));
    }

    /** True when the last notice of the proposal invoice is ENDED without handover. Unreadable data proves nothing. */
    public static boolean awaitingHandover(ContractStatus status, String invoiceId, String snapshot) {
        if (status != ContractStatus.TERMINATED || invoiceId == null || snapshot == null || snapshot.isBlank()) {
            return false;
        }
        try {
            JsonNode last = null;
            for (JsonNode notice : JSON.readTree(snapshot)) {
                if (invoiceId.equals(notice.path("invoiceId").asText(null))) last = notice;
            }
            return last != null && "ENDED".equals(last.path("status").asText(null)) && !last.hasNonNull("handoverAt");
        } catch (Exception unreadable) {
            return false;
        }
    }

    public LocalDate endExclusive() {
        if (agreedEarlyEndExclusive != null) return agreedEarlyEndExclusive;
        Integer months = billingMonths();
        return moveInDate == null || months == null ? null : moveInDate.plusMonths(months);
    }

    /** Effective number of lease months. */
    public Integer billingMonths() {
        return agreedEarlyBillingMonths != null ? agreedEarlyBillingMonths
                : effectiveLeaseMonths != null ? effectiveLeaseMonths : leaseMonths;
    }
}
