package com.hs.listing.dto.response;

import com.hs.listing.model.ListingStatusHistory;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.ListingStatusActorType;

import java.time.Instant;

public record ListingStatusHistoryResponse(
        String id,
        ListingStatus fromStatus,
        ListingStatus toStatus,
        String reason,
        String changedBy,
        ListingStatusActorType changedByType,
        Instant createdAt
) {
    public static ListingStatusHistoryResponse from(ListingStatusHistory history) {
        return new ListingStatusHistoryResponse(
                history.getId(), history.getFromStatus(), history.getToStatus(), history.getReason(),
                history.getChangedBy(), history.getChangedByType(), history.getCreatedAt());
    }
}
