package com.hs.listing.dto.response;

import com.hs.listing.model.ListingStatusHistory;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.ListingStatusActorType;
import com.hs.user.model.User;

import java.time.Instant;

public record ListingStatusHistoryResponse(
        String id,
        ListingStatus fromStatus,
        ListingStatus toStatus,
        String reason,
        String changedBy,
        String changedByDisplayName,
        ListingStatusActorType changedByType,
        Instant createdAt
) {
    public static ListingStatusHistoryResponse from(ListingStatusHistory history, User actor) {
        return new ListingStatusHistoryResponse(
                history.getId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getReason(),
                history.getChangedBy(),
                resolveDisplayName(history.getChangedBy(), history.getChangedByType(), actor),
                history.getChangedByType(),
                history.getCreatedAt());
    }

    private static String resolveDisplayName(
            String changedBy, ListingStatusActorType actorType, User actor) {
        if (actorType == ListingStatusActorType.SYSTEM || "SYSTEM".equalsIgnoreCase(changedBy)) {
            return "Hệ thống";
        }
        if (actor != null) {
            ListingOwnerResponse owner = ListingOwnerResponse.from(actor);
            if (owner != null && owner.displayName() != null && !owner.displayName().isBlank()) {
                return owner.displayName();
            }
        }
        return "Người dùng không xác định";
    }
}
