package com.hs.listing.service.surroundings;

import java.util.List;
import java.util.Map;

/** Business DTOs for the renter nearby-amenity comparison. No raw GeoJSON, contact data or provider URLs. */
public final class SurroundingsDtos {
    private SurroundingsDtos() {}

    /** All fields are validated server-side; provider categories can never be supplied by the caller. */
    public record Request(List<String> listingIds, List<String> renterProfiles, List<String> priorityCategories,
                          Integer radiusMeters, String targetPlaceQuery, Map<String, Integer> originalPositions) {}

    public record PoiSample(String name, String address, int distanceMeters, Integer providerDistanceMeters,
                            String coordinateSource) {}

    /** coverage: CHECKED (complete), PARTIAL (provider page was full/truncated), UNCHECKED (never queried). */
    public record CategoryEvidence(String category, String label, String coverage, int uniqueCount,
                                   boolean truncated, List<PoiSample> nearest) {}

    public record CandidateEvidence(String listingId, Integer originalPosition, String status, String statusDetail,
                                    List<CategoryEvidence> categories, boolean paginationComplete, int pagesFetched,
                                    String fetchedAt, String cache, List<String> warnings) {}

    public record Reason(String category, String label, String poiName, Integer distanceMeters) {}

    public record RankedListing(String listingId, int rank, Integer originalPosition, double score,
                                List<Reason> reasons, List<String> missing) {}

    public record TargetOption(String listingId, String name, String address, int distanceMeters) {}

    /** status: NOT_REQUESTED, CONFIRMED, NEEDS_CONFIRMATION, NOT_FOUND, NOT_EVALUATED. */
    public record Target(String query, String status, List<TargetOption> options) {}

    public record Source(String name, String attribution, String license, String url) {}

    public record Usage(int providerRequests, int cacheHits, int pages) {}

    public record Response(String status, int candidateCount, int comparedCount, int recommendationLimit,
                           int radiusMeters, List<String> profiles, List<String> categories, Target target,
                           List<CandidateEvidence> candidates, List<String> recommendedIds,
                           List<RankedListing> ranking, List<String> warnings, List<Source> sources,
                           Usage usage, String readAt) {}
}
