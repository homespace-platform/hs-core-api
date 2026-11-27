package com.hs.listing.dto.response;

import com.hs.listing.model.constant.ListingStatus;

import java.time.Instant;

/** Column projection for the branch overview. */
public record BranchOverviewListingRow(String id, ListingStatus status, ListingStatus reviewOperationalStatus,
                                       Instant expiresAt) {
    /** Same rule as Listing.getOperationalStatus(). */
    public ListingStatus operationalStatus() {
        return status == ListingStatus.PENDING_REVIEW && reviewOperationalStatus != null
                ? reviewOperationalStatus : status;
    }

    public boolean publiclyListed(Instant realNow) {
        return operationalStatus() == ListingStatus.PUBLISHED && (expiresAt == null || realNow.isBefore(expiresAt));
    }
}
