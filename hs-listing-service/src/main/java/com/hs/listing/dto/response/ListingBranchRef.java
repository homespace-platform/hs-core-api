package com.hs.listing.dto.response;

/** A listing's live branch, read without loading or locking the listing. */
public record ListingBranchRef(String listingId, String branchId) {
}
