package com.hs.listing.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * {@code total} counts listings proven to match. {@code unknownTotal} counts listings that matched nothing
 * false but lacked data for at least one condition (for example per-person rent without an occupant count):
 * they are excluded, never shown as matches.
 */
public record AiListingSearchResponse(List<String> listingIds, long total, Instant readAt, Integer version,
                                      String catalogVersion, Long unknownTotal, List<String> contextMissing,
                                      List<ListingEvidence> evidence) {
    public AiListingSearchResponse(List<String> listingIds, long total, Instant readAt) {
        this(listingIds, total, readAt, null, null, null, null, null);
    }

    public record ListingEvidence(String listingId, Double targetDistanceMeters, List<Check> checks) {}

    /** MATCH, NOT_MATCH or UNKNOWN for one compiled condition; {@code actual} only for computed scalars. */
    public record Check(String fields, String status, Object actual) {}
}
