package com.hs.listing.service.surroundings;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates a Geoapify GeoJSON FeatureCollection and normalizes it. Pure functions, no I/O. */
public final class PoiParser {
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private static final int MAX_DIAGNOSTICS = 20;

    public record ParseResult(List<Poi> pois, int skipped, int featureCount, List<String> diagnostics) {}

    private PoiParser() {}

    public static ParseResult parse(JsonNode root, double centerLatitude, double centerLongitude) {
        if (root == null || !root.isObject() || !"FeatureCollection".equals(root.path("type").asText())
                || !root.path("features").isArray()) {
            // A schema violation is an upstream error, never a successful empty result.
            throw new GeoapifyException(GeoapifyException.Code.INVALID_RESPONSE, "Geoapify response is not a FeatureCollection");
        }
        List<Poi> pois = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        int skipped = 0, index = 0;
        for (JsonNode feature : root.path("features")) {
            Poi poi = feature(feature, centerLatitude, centerLongitude);
            if (poi == null) {
                skipped++;
                if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add("feature[" + index + "] skipped: invalid or missing coordinates");
            } else {
                pois.add(poi);
            }
            index++;
        }
        return new ParseResult(List.copyOf(pois), skipped, index, List.copyOf(diagnostics));
    }

    private static Poi feature(JsonNode feature, double centerLat, double centerLon) {
        if (feature == null || !feature.isObject() || !feature.path("properties").isObject()) return null;
        JsonNode props = feature.path("properties");
        double[] chosen = null;
        String source = null;
        // Never mix latitude from one source with longitude from another: take one complete valid pair.
        double[] fromProps = pair(props.path("lat"), props.path("lon"));
        double[] fromGeometry = geometryPair(feature.path("geometry"));
        if (fromProps != null) { chosen = fromProps; source = "PROPERTIES"; }
        else if (fromGeometry != null) { chosen = fromGeometry; source = "GEOMETRY"; }
        if (chosen == null) return null;
        Double discrepancy = fromProps != null && fromGeometry != null
                ? haversineMeters(fromProps[0], fromProps[1], fromGeometry[0], fromGeometry[1]) : null;

        List<String> categories = new ArrayList<>();
        if (props.path("categories").isArray()) {
            for (JsonNode category : props.path("categories")) if (category.isTextual()) categories.add(category.asText());
        }
        JsonNode datasource = props.path("datasource");
        JsonNode raw = datasource.path("raw");
        String osm = raw.hasNonNull("osm_type") && raw.hasNonNull("osm_id")
                ? raw.path("osm_type").asText() + raw.path("osm_id").asText() : null;
        Integer providerDistance = null;
        JsonNode distance = props.path("distance");
        if (distance.isNumber() && distance.asDouble() >= 0 && Double.isFinite(distance.asDouble())) {
            providerDistance = (int) Math.round(distance.asDouble()); // meters, never multiplied
        }
        return new Poi(
                text(props, "place_id", 600), osm, text(props, "name", 255), text(props, "formatted", 500),
                List.copyOf(categories), chosen[0], chosen[1], source, discrepancy, providerDistance,
                haversineMeters(centerLat, centerLon, chosen[0], chosen[1]),
                text(datasource, "sourcename", 80), text(datasource, "attribution", 200),
                text(datasource, "license", 120), text(datasource, "url", 300));
    }

    private static double[] pair(JsonNode lat, JsonNode lon) {
        if (!lat.isNumber() || !lon.isNumber()) return null;
        double a = lat.asDouble(), b = lon.asDouble();
        return valid(a, b) ? new double[] {a, b} : null;
    }

    /** GeoJSON order is [longitude, latitude]. */
    private static double[] geometryPair(JsonNode geometry) {
        if (!"Point".equals(geometry.path("type").asText())) return null;
        JsonNode coordinates = geometry.path("coordinates");
        if (!coordinates.isArray() || coordinates.size() < 2) return null;
        return pair(coordinates.get(1), coordinates.get(0));
    }

    private static boolean valid(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
                && !(lat == 0 && lon == 0);
    }

    private static String text(JsonNode node, String key, int max) {
        JsonNode value = node.path(key);
        if (!value.isTextual()) return null;
        String trimmed = value.asText().strip();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1), dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Keep only POIs inside the circle. Compares the unrounded distance so the rim is not rounded in or out. */
    public static List<Poi> withinRadius(List<Poi> pois, int radiusMeters) {
        return pois.stream().filter(poi -> poi.straightLineDistanceMeters() <= radiusMeters).toList();
    }

    /**
     * Same place_id first, then the same OSM identity; provenance of merged categories is kept. Nearby
     * but different identities are never merged, so a campus with several nodes is not collapsed by distance.
     */
    public static List<Poi> dedupe(List<Poi> pois) {
        Map<String, Poi> byKey = new LinkedHashMap<>();
        for (Poi poi : pois) {
            String key = poi.placeId() != null ? "p:" + poi.placeId() : poi.osmIdentity() != null ? "o:" + poi.osmIdentity() : null;
            if (key == null) { byKey.put("anon:" + byKey.size(), poi); continue; }
            Poi existing = byKey.get(key);
            if (existing == null && poi.osmIdentity() != null) {
                existing = byKey.get("o:" + poi.osmIdentity());
                if (existing != null) key = "o:" + poi.osmIdentity();
            }
            byKey.put(key, existing == null ? poi : merge(existing, poi));
        }
        // A place_id entry and an OSM-identity entry can still point at the same OSM object.
        Map<String, Poi> byOsm = new LinkedHashMap<>();
        List<Poi> result = new ArrayList<>();
        for (Poi poi : byKey.values()) {
            if (poi.osmIdentity() == null) { result.add(poi); continue; }
            Poi existing = byOsm.get(poi.osmIdentity());
            if (existing == null) { byOsm.put(poi.osmIdentity(), poi); result.add(poi); }
            else { Poi merged = merge(existing, poi); byOsm.put(poi.osmIdentity(), merged); result.set(result.indexOf(existing), merged); }
        }
        return List.copyOf(result);
    }

    private static Poi merge(Poi a, Poi b) {
        List<String> categories = new ArrayList<>(a.categories());
        for (String category : b.categories()) if (!categories.contains(category)) categories.add(category);
        return new Poi(a.placeId(), a.osmIdentity(), a.name() != null ? a.name() : b.name(), a.formatted() != null ? a.formatted() : b.formatted(),
                List.copyOf(categories), a.latitude(), a.longitude(), a.coordinateSource(), a.coordinateDiscrepancyMeters(),
                a.providerDistanceMeters() != null ? a.providerDistanceMeters() : b.providerDistanceMeters(),
                a.straightLineDistanceMeters(), a.sourceName(), a.attribution(), a.license(), a.sourceUrl());
    }
}
