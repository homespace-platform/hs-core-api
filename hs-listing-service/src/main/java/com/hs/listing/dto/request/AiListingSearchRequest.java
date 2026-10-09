package com.hs.listing.dto.request;

import java.util.List;

/** Strict, read-only predicates for the AI listing tool. Unknown fields never become no-op filters. */
public record AiListingSearchRequest(List<Filter> filters, String sort, Integer limit) {
    public record Filter(String field, String op, Object value) {}
}
