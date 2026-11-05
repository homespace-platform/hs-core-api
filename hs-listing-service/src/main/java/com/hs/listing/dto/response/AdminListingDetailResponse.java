package com.hs.listing.dto.response;

import com.hs.listing.dto.request.CreateListingRequest;
import java.util.List;

public record AdminListingDetailResponse(
        ListingDetailResponse listing,
        List<ListingStatusHistoryResponse> statusHistory,
        String pendingEditId,
        CreateListingRequest pendingEdit,
        String pendingAddress,
        String reviewError,
        boolean protectedRental
) {
}
