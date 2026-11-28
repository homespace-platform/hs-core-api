package com.hs.contract.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hs.contract.dto.branch.BranchAttentionResponse.Priority;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.dto.branch.BranchOverviewResponse.RoomStatus;
import com.hs.contract.service.BranchAttentionService.ExpiringLease;
import com.hs.listing.model.constant.AppointmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Landlord dashboard. Every key is always present; a group that was not asked for or failed is null. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LandlordDashboardResponse(Instant billingNow, Instant realNow, String month,
                                        Map<String, Boolean> errors, Setup setup, Rooms rooms, Listings listings,
                                        Revenue revenue, Todo todo, Contracts contracts,
                                        List<ExpiringLease> expiringContracts, List<Visit> appointments,
                                        List<ExpiringListing> expiringListings, Membership membership,
                                        List<Competing> competing, List<Hold> holds, Complaints complaints) {

    public record Setup(boolean hasReceivingAccount) {}

    public record Rooms(int total, int occupied, int vacant, int reserved, int other,
                        Map<RoomStatus, Integer> byStatus) {}

    public record Listings(int published, int pendingReview, int draft, Instant oldestPendingSince,
                           Unplaced unplaced, NeedsFix needsFix) {}
    public record Unplaced(long listings, long contracts) {}
    public record NeedsFix(long rejected, long violation, long hidden, List<ListingIssue> items) {}
    public record ListingIssue(String id, String title, String status, String reason, Instant updatedAt, String href) {}

    /** Money in VND exactly as stored (scale 2). received/refunded/expected never include deposits. */
    public record Revenue(BigDecimal receivedVnd, BigDecimal refundedVnd, BigDecimal expectedVnd, long receiptCount,
                          long overdueCount, BigDecimal overdueVnd, BigDecimal depositReceivedVnd,
                          Pending awaitingConfirm, Pending rejected, Pending refundsOwed,
                          BigDecimal lateFromPreviousVnd, long autoConfirmed30d, BigDecimal lateFeeVnd,
                          BigDecimal rolledOverVnd, long receiptPdfIssues) {}

    /** {@code nextDueAt}: earliest confirmation deadline (awaitingConfirm) or due time (rejected, refundsOwed). */
    public record Pending(long count, BigDecimal vnd, Instant nextDueAt) {}

    public record Todo(int total, int urgent, Map<String, Integer> groups, List<TodoItem> items) {}

    public record TodoItem(String key, String type, String roomCode, String contextLabel, Instant dueAt,
                           Clock clock, Priority priority, String href) {}

    public record Contracts(Map<String, Integer> byStatus, Closures closures, SignatureIssues signatureIssues,
                            DepositAgreements depositAgreements) {}
    public record Closures(Map<String, Long> byStatus, long endedNotClosed, BigDecimal refundVnd, Instant nextRefundDueAt) {}
    public record SignatureIssues(long failed, long pendingConfirm, Instant nearestExpiresAt) {}
    public record DepositAgreements(long waitingSign, Instant nextDueAt) {}

    public record Visit(String id, Instant at, String listingTitle, String renterName, AppointmentStatus status,
                        String href) {}

    public record ExpiringListing(String id, String title, Instant expiresAt, int daysRemaining, long viewCount,
                                  String href, long favorites, boolean noLeads) {}

    public record Membership(String tier, int remainingUses, int reservedUses, Instant expiresAt,
                             Instant quotaResetsAt, int addonRemaining, int addonReserved, Instant addonExpiresAt,
                             boolean unlimited, long pendingOrders, Instant nextOrderExpiresAt,
                             long failedOrders30d, BigDecimal paidVndMonth) {}
    public record Competing(String listingId, String title, long pendingCount, Instant nextRespondBy, String href) {}
    public record Hold(String rentalRequestId, String listingTitle, String renterName, Instant holdExpiresAt,
                       String waitingFor, String href) {}
    public record Complaints(long openCount, long dueFromMeCount, List<Complaint> items) {}
    public record Complaint(String id, String contractNumber, String category, String status, Instant respondBy,
                            String responseDueFor, String href) {}
}
