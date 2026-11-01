package com.hs.listing.dto.request;

import java.util.List;

/** Read-only cost projection of a displayed shortlist. No move-in date or lease assumptions. */
public record AiListingCostRequest(List<String> listingIds, AiListingSearchRequest.SearchContext context) {}
