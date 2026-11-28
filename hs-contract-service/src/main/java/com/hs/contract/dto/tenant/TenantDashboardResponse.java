package com.hs.contract.dto.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hs.contract.dto.branch.BranchAttentionResponse.Priority;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.dto.branch.BranchOverviewResponse.ExpiryStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Tenant dashboard. Every key is always present (empty list / zero when there is nothing); nullable values are sent
 * as null. Deliberately no bank snapshot, QR, phone, email, national id or free-text reason of another party.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TenantDashboardResponse(Instant billingNow, Instant realNow, String timezone, Setup setup, Leases leases,
                                      Payables payables, Tasks tasks, List<Hold> holds, Visits visits,
                                      Requests requests, Spending spending, Deposits deposits, Waiting waiting,
                                      Complaints complaints, Saved saved) {

    public record Setup(boolean onboarded) {}

    public record Deposits(BigDecimal heldTotal, List<Deposit> items) {}
    public record Deposit(String contractId, String contractNumber, BigDecimal amount, String status,
                          Closure closure, String href) {}
    public record Closure(String status, BigDecimal refundAmount, Instant refundDueAt) {}
    public record Waiting(int count, List<WaitingItem> items) {}
    public record WaitingItem(String type, String contractId, String contractNumber, Instant sentAt,
                              Instant respondBy, Clock clock, String href) {}

    public record Leases(int activeCount, List<Lease> items) {}

    public record Lease(String contractId, String contractNumber, String listingId, String listingTitle,
                        String listingAddress, String listingThumbnail, String landlordName, LocalDate startsOn,
                        LocalDate endExclusive, Long daysRemaining, ExpiryStatus expiry, BigDecimal monthlyRent,
                        Payable nextPayment, String href) {}

    public record Payables(int overdueCount, BigDecimal overdueAmount, BigDecimal dueSoonAmount, Payable next,
                           List<Payable> items, Awaiting awaiting, RefundsIncoming refundsIncoming) {}

    public record Payable(String paymentId, String type, String contractId, String contractNumber,
                          String listingTitle, String invoiceId, Integer periodIndex, BigDecimal amount,
                          Instant dueAt, Clock clock, boolean overdue, String rejectedReason, String href) {}

    /** Reported by the tenant, waiting for the landlord; not part of "to pay". */
    public record Awaiting(int count, BigDecimal amount, List<AwaitingItem> items) {}

    public record AwaitingItem(String paymentId, String contractNumber, BigDecimal amount, Instant payerReportedAt,
                               Instant confirmationDueAt, String href) {}

    public record RefundsIncoming(int count, BigDecimal amount, List<RefundItem> items) {}

    public record RefundItem(String paymentId, String contractNumber, String type, String status, BigDecimal amount,
                             Instant dueAt, Instant autoConfirmAt, String href) {}

    public record Tasks(int total, int urgentCount, List<Task> items) {}

    public record Task(String key, String type, String contractId, String rentalRequestId, String contextLabel,
                       Instant dueAt, Clock clock, Priority priority, String href) {}

    /** {@code critical}: red on the client, 15 minutes or less left (owner decision D8). */
    public record Hold(String rentalRequestId, String listingTitle, String thumbnail, Instant holdExpiresAt,
                       long remainingSeconds, boolean critical, BigDecimal amountDue, String href) {}

    public record Visits(int upcomingCount, List<Visit> items) {}

    public record Visit(String id, String listingId, String listingTitle, String listingAddress, String thumbnail,
                        LocalDate appointmentDate, LocalTime startTime, LocalTime endTime, String status,
                        boolean rescheduleRequested, LocalDate proposedDate, LocalTime proposedStartTime) {}

    public record Requests(Map<String, Long> counts, List<OpenRequest> open) {}

    public record OpenRequest(String id, String listingTitle, String thumbnail, String status, Instant createdAt,
                              Instant respondBy, Instant holdExpiresAt, String href) {}

    /** Receipts where the user paid the landlord. Deposit is its own group; membership fees are not included. */
    public record Spending(String currency, BigDecimal total, BigDecimal totalExcludingDeposit,
                           BigDecimal total12m, BigDecimal total12mExcludingDeposit,
                           List<SpendMonth> months, int receiptPdfIssues) {}

    public record SpendMonth(String month, BigDecimal total, Map<String, BigDecimal> byGroup) {}

    public record Complaints(int openCount, int dueFromMeCount, List<Complaint> items) {}

    /** No reason/decision text and no id of the other party. */
    public record Complaint(String id, String contractNumber, String category, String status, Instant respondBy,
                            String responseDueFor, String href) {}

    public record Saved(long total, long available, long unavailable) {}
}
