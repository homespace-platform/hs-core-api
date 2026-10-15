package com.hs.listing.service;

import com.hs.listing.dto.snapshot.ListingSnapshotDto.AmenityItemSnapshot;
import com.hs.listing.model.*;
import java.util.*;

/** Copy only amenities actually selected on the listing, just like ListingQueryService. */
public final class ListingAmenitySnapshots {
    private ListingAmenitySnapshots() {}

    public static List<AmenityItemSnapshot> resolve(Listing listing, PropertyBranch ignoredBranch) {
        var rows = new LinkedHashMap<String,AmenityItemSnapshot>();
        if (listing.getAmenities() != null) listing.getAmenities().stream()
                .filter(a -> a != null && a.getCode() != null && a.getName() != null && !"PETS_ALLOWED".equals(a.getCode()))
                .sorted(Comparator.comparing(Amenity::getSortOrder,Comparator.nullsLast(Integer::compareTo)).thenComparing(Amenity::getCode))
                .forEach(a -> rows.put(a.getCode(),row(a.getCode(),a.getName(),"LISTING")));
        // A branch catalog is not proof that an amenity was included in this particular listing.
        if (listing.getCustomAmenities() != null) listing.getCustomAmenities().stream()
                .filter(a -> a != null && a.getName() != null && !a.getName().isBlank())
                .sorted(Comparator.comparing(ListingCustomAmenity::getName))
                .forEach(a -> rows.put("CUSTOM:" + a.getName(),row("CUSTOM",a.getName(),"CUSTOM")));
        return new ArrayList<>(rows.values());
    }

    private static AmenityItemSnapshot row(String code,String name,String source) {
        return AmenityItemSnapshot.builder().code(code).name(name).sourceType(source).build();
    }
}
