package com.hs.listing.service.surroundings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class PoiParserTest {
    static final double LAT = 10.8336482, LON = 106.6895746; // Postman sample centre, test data only
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/geoapify/universities_sample.json")) {
            return mapper.readTree(in);
        }
    }

    @Test
    void parsesUserFixtureKeepingProviderDistanceInMetersAndProvenance() throws Exception {
        PoiParser.ParseResult result = PoiParser.parse(fixture(), LAT, LON);
        assertThat(result.featureCount()).isEqualTo(10);
        assertThat(result.pois()).hasSize(10);
        Poi iuh = result.pois().stream().filter(p -> p.providerDistanceMeters() == 1296).findFirst().orElseThrow();
        assertThat(iuh.name()).contains("Công nghiệp");
        assertThat(iuh.coordinateSource()).isEqualTo("PROPERTIES");
        assertThat(iuh.coordinateDiscrepancyMeters()).isNotNull().isGreaterThan(0.0);
        // Haversine is stored separately and is allowed to differ slightly from the provider distance.
        assertThat(iuh.straightLineDistanceMeters()).isBetween(1100.0, 1500.0);
        assertThat(iuh.categories()).contains("education.university");
        assertThat(iuh.attribution()).contains("OpenStreetMap");
    }

    @Test
    void unnamedFeatureKeepsNullNameAndIsNeverRenamedFromANeighbour() throws Exception {
        PoiParser.ParseResult result = PoiParser.parse(fixture(), LAT, LON);
        Poi unnamed = result.pois().stream().filter(p -> p.providerDistanceMeters() == 1297).findFirst().orElseThrow();
        assertThat(unnamed.name()).isNull();
        assertThat(unnamed.named()).isFalse();
        assertThat(unnamed.categories()).contains("education.university");
    }

    @Test
    void cityNameFromProviderDoesNotChangeAnything() throws Exception {
        // Fixture POIs carry city "Thuận An"; the parser exposes no administrative field that could override a listing address.
        assertThat(Poi.class.getRecordComponents()).extracting(c -> c.getName()).doesNotContain("city", "district", "ward");
    }

    @Test
    void usesGeometryOnlyWhenThePropertyPairIsInvalidAndNeverMixesAxes() throws Exception {
        JsonNode root = mapper.readTree("{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"lat\":10.8,\"name\":\"Only lat\",\"categories\":[\"education.school\"]},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[106.7,10.81]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"name\":\"No coords\"}}]}");
        PoiParser.ParseResult result = PoiParser.parse(root, LAT, LON);
        assertThat(result.pois()).hasSize(1);
        assertThat(result.pois().get(0).coordinateSource()).isEqualTo("GEOMETRY");
        assertThat(result.pois().get(0).latitude()).isEqualTo(10.81); // from geometry pair, not mixed with property lat 10.8
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.diagnostics()).hasSize(1);
    }

    @Test
    void wrongSchemaIsAnErrorNotAnEmptySuccess() throws Exception {
        for (String body : List.of("{}", "[]", "{\"type\":\"FeatureCollection\"}", "{\"type\":\"Other\",\"features\":[]}")) {
            assertThatThrownBy(() -> PoiParser.parse(mapper.readTree(body), LAT, LON))
                    .isInstanceOfSatisfying(GeoapifyException.class, e -> assertThat(e.code()).isEqualTo(GeoapifyException.Code.INVALID_RESPONSE));
        }
        assertThat(PoiParser.parse(mapper.readTree("{\"type\":\"FeatureCollection\",\"features\":[]}"), LAT, LON).pois()).isEmpty();
    }

    @Test
    void missingOrInvalidDistanceIsNullNotZero() throws Exception {
        String feature = "{\"type\":\"Feature\",\"properties\":{\"lat\":10.83,\"lon\":106.69,\"distance\":%s,\"categories\":[]},\"geometry\":null}";
        for (String value : List.of("null", "-5", "\"abc\"")) {
            JsonNode root = mapper.readTree("{\"type\":\"FeatureCollection\",\"features\":[" + feature.formatted(value) + "]}");
            assertThat(PoiParser.parse(root, LAT, LON).pois().get(0).providerDistanceMeters()).isNull();
        }
        JsonNode zero = mapper.readTree("{\"type\":\"FeatureCollection\",\"features\":[" + feature.formatted("0") + "]}");
        assertThat(PoiParser.parse(zero, LAT, LON).pois().get(0).providerDistanceMeters()).isZero();
    }

    @Test
    void radiusFilterComparesUnroundedDistanceAtTheRim() {
        // 0.009 degrees of latitude is about 1000.8 m.
        double northRim = LAT + 0.009;
        double metres = PoiParser.haversineMeters(LAT, LON, northRim, LON);
        assertThat(metres).isBetween(1000.0, 1002.0);
        Poi poi = new Poi("a", null, "x", null, List.of(), northRim, LON, "PROPERTIES", null, null, metres, null, null, null, null);
        assertThat(PoiParser.withinRadius(List.of(poi), 1001)).hasSize(1);
        assertThat(PoiParser.withinRadius(List.of(poi), 1000)).isEmpty(); // 1000.8 rounds to 1001 but is outside a 1000 m circle
        assertThat(PoiParser.withinRadius(List.of(poi), 5000)).hasSize(1);
    }

    @Test
    void dedupeUsesPlaceIdThenOsmIdentityButNeverProximity() {
        Poi a = poi("pid-1", "w1", "Trường A", List.of("education.university"), 10.0000, 106.0000);
        Poi sameId = poi("pid-1", "w1", "Trường A", List.of("education"), 10.0000, 106.0000);
        Poi sameOsmOtherPlaceId = poi("pid-2", "w1", "Trường A", List.of("commercial.x"), 10.0000, 106.0000);
        Poi neighbour = poi("pid-3", "n9", "Trường A", List.of("education.university"), 10.00001, 106.00001);
        List<Poi> result = PoiParser.dedupe(List.of(a, sameId, sameOsmOtherPlaceId, neighbour));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).categories()).contains("education.university", "education", "commercial.x");
    }

    private static Poi poi(String id, String osm, String name, List<String> categories, double lat, double lon) {
        return new Poi(id, osm, name, null, categories, lat, lon, "PROPERTIES", null, null,
                PoiParser.haversineMeters(LAT, LON, lat, lon), null, null, null, null);
    }
}
