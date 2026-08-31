package com.hs.listing.dto.response;

import java.util.List;

public record AdminListingDetailResponse(
        ListingDetailResponse listing,
        List<ListingStatusHistoryResponse> statusHistory
) {
}
