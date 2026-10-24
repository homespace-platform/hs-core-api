package com.hs.listing.dto.response;

import java.util.List;

/** A listing with at least two PENDING rental requests competing for it (owner view). */
public record RentalRequestConflictResponse(
        String listingId,
        String listingTitle,
        String listingAddress,
        int pendingCount,
        List<String> requestIds) {
}
