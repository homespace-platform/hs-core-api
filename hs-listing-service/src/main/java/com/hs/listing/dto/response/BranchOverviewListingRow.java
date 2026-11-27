package com.hs.listing.dto.response;

import com.hs.listing.model.constant.ListingStatus;

import java.time.Instant;

/** Column projection for the branch overview. */
public record BranchOverviewListingRow(String id, ListingStatus status, ListingStatus reviewOperationalStatus,
                                       Instant expiresAt) {
    public boolean publiclyListed(Instant realNow) {
        ListingStatus operational = status == ListingStatus.PENDING_REVIEW && reviewOperationalStatus != null
                ? reviewOperationalStatus : status;
        return operational == ListingStatus.PUBLISHED && (expiresAt == null || realNow.isBefore(expiresAt));
    }
}
