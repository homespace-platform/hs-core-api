package com.hs.listing.dto.request;

import java.util.List;

/**
 * Strict, read-only predicates for the AI listing tool. Unknown fields never become no-op filters.
 * {@code keyword} is a free-text term matched against title, description or address; named places use
 * {@code nearby}/{@code nearbyAll} with a stored target instead.
 *
 * <p>Version 2 adds an AND/OR condition tree ({@code where}), several nearby predicates, a renter context
 * for computed monthly costs, and scope/exclusion lists. All predicates are applied before count and LIMIT.
 */
public record AiListingSearchRequest(List<Filter> filters, String sort, Integer limit, String keyword, NearbyCriteria nearby,
                                     Integer version, Condition where, List<NearbyCriteria> nearbyAll, SearchContext context,
                                     List<String> scopeIds, List<String> excludeIds, Boolean evidence) {
    public AiListingSearchRequest(List<Filter> filters, String sort, Integer limit, String keyword, NearbyCriteria nearby) {
        this(filters, sort, limit, keyword, nearby, null, null, null, null, null, null, null);
    }
    public AiListingSearchRequest(List<Filter> filters, String sort, Integer limit, String keyword) {
        this(filters, sort, limit, keyword, null);
    }
    public AiListingSearchRequest(List<Filter> filters, String sort, Integer limit) {
        this(filters, sort, limit, null);
    }

    public record Filter(String field, String op, Object value) {}

    /** Exactly one of: a leaf ({@code field}/{@code op}/{@code value}), {@code all} (AND) or {@code any} (OR). */
    public record Condition(String field, String op, Object value, List<Condition> all, List<Condition> any) {
        public static Condition leaf(String field, String op, Object value) { return new Condition(field, op, value, null, null); }
        public static Condition allOf(List<Condition> all) { return new Condition(null, null, null, all, null); }
        public static Condition anyOf(List<Condition> any) { return new Condition(null, null, null, null, any); }
    }

    /**
     * A named place ({@code placeId}, straight-line distance from stored coordinates) or a cached nearby
     * group, optionally narrowed to a verified provider category {@code kind}.
     */
    public record NearbyCriteria(String placeId, com.hs.listing.nearby.NearbyTypes.Group group, Integer radiusMeters, String kind) {
        public NearbyCriteria(String placeId, com.hs.listing.nearby.NearbyTypes.Group group, Integer radiusMeters) {
            this(placeId, group, radiusMeters, null);
        }
    }

    /** Renter facts used only by computed fields; unknown values make those fields UNKNOWN, never zero. */
    public record SearchContext(Integer occupants, Integer motorbikes, Integer cars, Integer electricityKwh, Integer waterM3) {}
}
