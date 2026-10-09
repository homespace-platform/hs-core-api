package com.hs.listing.dto.response;

import java.time.Instant;
import java.util.List;

public record AiListingSearchResponse(List<String> listingIds, long total, Instant readAt) {}
