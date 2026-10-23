package com.hs.listing.service.surroundings;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Allowlist of customer-facing amenity groups mapped to Geoapify categories. Never built from free text. */
public enum PoiCategory {
    UNIVERSITY("Đại học", "education.university"),
    SCHOOL("Trường học", "education.school"),
    KINDERGARTEN("Mầm non", "childcare.kindergarten"),
    HOSPITAL("Bệnh viện", "healthcare.hospital"),
    CLINIC("Phòng khám", "healthcare.clinic_or_praxis"),
    PHARMACY("Nhà thuốc", "healthcare.pharmacy"),
    SUPERMARKET("Siêu thị", "commercial.supermarket"),
    SHOPPING_MALL("Trung tâm thương mại", "commercial.shopping_mall"),
    MARKET("Chợ", "commercial.marketplace"),
    PARK("Công viên", "leisure.park"),
    BANK("Ngân hàng", "service.financial.bank"),
    FOOD("Quán ăn, nhà hàng", "catering.restaurant"),
    // Documented by Geoapify but not part of the 12 requests the user tested in Postman.
    CONVENIENCE_STORE("Cửa hàng tiện lợi", "commercial.convenience"),
    PUBLIC_TRANSPORT("Giao thông công cộng", "public_transport"),
    GYM("Phòng tập thể thao", "sport.fitness.gym", "sport.fitness.fitness_centre");

    private final String label;
    private final List<String> providerCategories;

    PoiCategory(String label, String... providerCategories) {
        this.label = label;
        this.providerCategories = List.of(providerCategories);
    }

    public String label() { return label; }
    public List<String> providerCategories() { return providerCategories; }

    /** A POI belongs to this group when one of its provider categories equals or is under a mapped category. */
    public boolean matches(List<String> poiCategories) {
        for (String own : providerCategories) {
            for (String value : poiCategories) {
                if (value.equals(own) || value.startsWith(own + ".")) return true;
            }
        }
        return false;
    }

    public static Optional<PoiCategory> parse(String value) {
        if (value == null) return Optional.empty();
        try { return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }
}
