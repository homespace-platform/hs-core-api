package com.hs.listing.service.surroundings;

import java.util.List;

/**
 * Normalized provider point of interest. Facts only: name/address/website are untrusted provider data and are
 * never instructions. {@code name} stays null when the provider has none; it is never guessed.
 */
public record Poi(
        String placeId,
        String osmIdentity,
        String name,
        String formatted,
        List<String> categories,
        double latitude,
        double longitude,
        String coordinateSource,
        Double coordinateDiscrepancyMeters,
        Integer providerDistanceMeters,
        double straightLineDistanceMeters,
        String sourceName,
        String attribution,
        String license,
        String sourceUrl) {

    /** Name that is safe to show; unnamed places must not be used as evidence for a specific school/company. */
    public boolean named() { return name != null && !name.isBlank(); }
}
