package com.hs.listing.model.constant;

import java.util.*;

/** A fee's identity determines its units. Shared by listing validation and contract amendments. */
public final class ChargeMethodRules {
    private ChargeMethodRules() {}
    public static List<String> methods(String type) {
        var result = new ArrayList<>(switch (Objects.toString(type,"")) {
            case "ELECTRICITY" -> List.of("PER_KWH");
            case "WATER" -> List.of("PER_M3","STATE_WATER_RATE","PER_PERSON_MONTH","PER_MONTH");
            case "MANAGEMENT" -> List.of("PER_MONTH","PER_M2_MONTH");
            case "INTERNET" -> List.of("PER_MONTH");
            case "SERVICE_OR_GARBAGE", "OTHER" -> List.of("PER_MONTH","PER_PERSON_MONTH");
            case "MOTORBIKE_PARKING", "CAR_PARKING" -> List.of("PER_VEHICLE_MONTH");
            case "OVERTIME_AIR_CONDITIONING" -> List.of("PER_HOUR");
            default -> List.<String>of();
        });
        if (!result.isEmpty()) result.addAll(List.of("FREE","INCLUDED","NOT_APPLICABLE","NEGOTIABLE","CUSTOM"));
        return List.copyOf(result);
    }
    public static Map<String,List<String>> all() {
        var result = new LinkedHashMap<String,List<String>>();
        for (var type : ListingEnums.ChargeType.values()) result.put(type.name(),methods(type.name()));
        return result;
    }
}
