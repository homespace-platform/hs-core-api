package com.hs.listing.dto.request;

import java.util.List;

/** Original hard criteria, current page and previously suggested references. Never carries client prices/facts. */
public record AiListingValueRequest(AiListingSearchRequest search, List<String> displayedIds, List<String> excludedSuggestionIds) {
    public AiListingValueRequest(AiListingSearchRequest search, List<String> displayedIds) {
        this(search, displayedIds, null);
    }
}
