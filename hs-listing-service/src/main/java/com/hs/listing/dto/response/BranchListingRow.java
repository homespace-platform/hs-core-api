package com.hs.listing.dto.response;

import com.hs.listing.model.constant.ListingStatus;

/** Column projection: loading Listing entities would fire one query per inverse one-to-one detail. */
public record BranchListingRow(String id, String title, ListingStatus status, ListingStatus reviewOperationalStatus) {
    public ListingStatus operationalStatus() {
        return status == ListingStatus.PENDING_REVIEW && reviewOperationalStatus != null ? reviewOperationalStatus : status;
    }
}
