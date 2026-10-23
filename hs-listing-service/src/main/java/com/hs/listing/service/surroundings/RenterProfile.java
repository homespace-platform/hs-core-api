package com.hs.listing.service.surroundings;

import static com.hs.listing.service.surroundings.PoiCategory.*;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Default amenity scan per renter group. Explicit customer priorities always rank above these presets. */
public enum RenterProfile {
    STUDENT(UNIVERSITY, FOOD, MARKET, SUPERMARKET, PUBLIC_TRANSPORT),
    WORKER(PUBLIC_TRANSPORT, SUPERMARKET, CONVENIENCE_STORE, FOOD),
    // Age of the child is unknown: school and kindergarten are both scanned, none is assumed.
    FAMILY_WITH_CHILDREN(KINDERGARTEN, SCHOOL, HOSPITAL, CLINIC, PHARMACY, PARK, SUPERMARKET),
    REMOTE_WORKER(SUPERMARKET, CONVENIENCE_STORE, PARK, GYM),
    CUSTOM();

    private final List<PoiCategory> presets;

    RenterProfile(PoiCategory... presets) { this.presets = List.of(presets); }

    public List<PoiCategory> presets() { return presets; }

    public static Optional<RenterProfile> parse(String value) {
        if (value == null) return Optional.empty();
        try { return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }
}
