package com.hs.contract.dto.branch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.dto.branch.BranchOverviewResponse.Target;

import java.time.Instant;
import java.util.List;

/** "Cần chú ý" of one branch, one page. Every key is always present; nullable values are sent as null. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BranchAttentionResponse(String branchId, Instant billingNow, Instant realNow, List<Work> items,
                                      int page, int size, int totalElements) {

    public enum WorkType {
        METER_UPCOMING, METER_TODAY, DRAFT_MISSED, METER_AUTOFILLED,
        PAYMENT_CONFIRM, PAYMENT_OVERDUE, PAYMENT_EXTENSION_REQUEST, OWNER_REFUND,
        OWNER_SIGN, AMENDMENT_RESPONSE, TERMINATION_RESPONSE, LEASE_EXPIRING,
        RENTAL_REQUEST_PENDING, VIEWING_PENDING,
        ROOM_VACANT_UNLISTED, LISTING_REJECTED, LISTING_EXPIRED,
        UNPLACED_CONTRACT, UNPLACED_LISTING,
        PAYMENT_REVIEW, CANCELLATION_DECISION, SIGNING_REOPEN
    }

    /** Declaration order is the display order. */
    public enum Priority { URGENT, SOON, NORMAL }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Work(String key, WorkType type, String roomId, String roomCode, String contextLabel,
                       Integer periodIndex, Instant dueAt, Clock clock, Priority priority, Target target) {}

    /** Every active branch of the owner, most urgent first. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(Instant billingNow, Instant realNow, List<BranchCount> branches) {}

    public record BranchCount(String branchId, int count) {}
}
