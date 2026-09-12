package com.hs.listing.model.constant;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ListingCategory {
    APARTMENT,
    HOUSE,
    OFFICE,
    COMMERCIAL_SPACE,
    ROOM;

    @JsonCreator
    public static ListingCategory fromString(String value) {
        if (value == null || value.isBlank()) return null;
        String val = value.trim().toUpperCase(java.util.Locale.ROOT);
        if ("COMMERCIAL".equals(val)) return COMMERCIAL_SPACE;
        for (ListingCategory category : ListingCategory.values()) {
            if (category.name().equalsIgnoreCase(val)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown ListingCategory: " + value);
    }
}
