package com.hs.listing.dto.request;

import java.util.List;

/**
 * Strict, read-only predicates for the AI listing tool. Unknown fields never become no-op filters.
 * {@code keyword} is a free-text place/landmark term matched like the public /rent keyword search
 * (title, description or address), used for landmarks such as "ĐH Quốc gia" that are not an administrative unit.
 */
public record AiListingSearchRequest(List<Filter> filters, String sort, Integer limit, String keyword) {
    public AiListingSearchRequest(List<Filter> filters, String sort, Integer limit) {
        this(filters, sort, limit, null);
    }

    public record Filter(String field, String op, Object value) {}
}
