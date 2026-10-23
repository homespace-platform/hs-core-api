package com.hs.listing.service.surroundings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hs.listing.service.surroundings.SurroundingsDtos.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class SurroundingsServiceTest {
    private static final double METER = 1.0 / 111_195.0;
    private static final String BASE_LAT = "10.8000000";

    /** Records every outbound request and answers from a function of the decoded query. */
    static final class Provider {
        final List<Map<String, String>> requests = new ArrayList<>();
        Function<Map<String, String>, String> answer = q -> collection();

        GeoapifyClient.Response handle(URI uri) {
            Map<String, String> q = new LinkedHashMap<>();
            for (String part : uri.getRawQuery().split("&")) {
                String[] kv = part.split("=", 2);
                q.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            requests.add(q);
            return new GeoapifyClient.Response(200, answer.apply(q).getBytes(StandardCharsets.UTF_8), null);
        }
    }

    static String collection(String... features) { return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}"; }

    static String feature(String placeId, String name, double lat, double lon, String... categories) {
        String cats = String.join(",", Arrays.stream(categories).map(c -> "\"" + c + "\"").toList());
        return "{\"type\":\"Feature\",\"properties\":{\"place_id\":\"" + placeId + "\"" + (name == null ? "" : ",\"name\":\"" + name + "\"")
                + ",\"lat\":" + lat + ",\"lon\":" + lon + ",\"categories\":[" + cats + "],\"formatted\":\"" + (name == null ? "" : name) + " addr\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}}";
    }

    static double[] circle(Map<String, String> q) {
        String[] parts = q.get("filter").substring("circle:".length()).split(",");
        return new double[] {Double.parseDouble(parts[1]), Double.parseDouble(parts[0]), Double.parseDouble(parts[2])};
    }

    static String id(int n) { return String.format("00000000-0000-0000-0000-%012d", n); }

    private final Map<String, SurroundingsService.Row> rows = new HashMap<>();

    private SurroundingsService service(Provider provider, String key, int pageSize, int maxPages, int maxRequests) {
        GeoapifyClient client = new GeoapifyClient(key, "https://api.geoapify.com", Duration.ofSeconds(10), (uri, t) -> provider.handle(uri));
        ConcurrentHashMap<String, Long> counts = new ConcurrentHashMap<>();
        PoiGate gate = new PoiGate((k, ttl) -> counts.merge(k, 1L, Long::sum), 1000, 60, 4, () -> 0L);
        PoiFetcher fetcher = new PoiFetcher(client, gate, pageSize, 3600, 300);
        return new SurroundingsService(new NamedParameterJdbcTemplate(mock(DataSource.class)), fetcher, 3000, 10000, 10, 3, maxPages, maxRequests, 25) {
            @Override Map<String, Row> load(List<String> ids) { return rows; }
        };
    }

    /** Candidate n sits at its own latitude; its supermarket is n*200 m north, so smaller n is closer. */
    private void listings(int count) {
        for (int n = 1; n <= count; n++) {
            rows.put(id(n), new SurroundingsService.Row(id(n), true, Double.parseDouble(BASE_LAT) + n * 0.01, 106.7));
        }
    }

    private static Provider supermarkets() {
        Provider provider = new Provider();
        provider.answer = q -> {
            double[] c = circle(q);
            return collection(feature("sm-" + Math.round(c[0] * 1e5), "Siêu thị " + Math.round(c[0] * 100), c[0] + ((Math.round(c[0] * 100) - 1080) * 200) * METER, c[1], "commercial.supermarket"));
        };
        return provider;
    }

    private static Request request(int count, String... priorities) {
        List<String> ids = new ArrayList<>();
        for (int n = 1; n <= count; n++) ids.add(id(n));
        return new Request(ids, List.of(), List.of(priorities), null, null, null);
    }

    @Test
    void fiveCandidatesStillRecommendAtMostThreeNearestFirst() {
        listings(5);
        Provider provider = supermarkets();
        Response response = service(provider, "k", 100, 2, 24).compare(request(5, "SUPERMARKET"));
        assertThat(response.candidateCount()).isEqualTo(5);
        assertThat(response.comparedCount()).isEqualTo(5);
        assertThat(response.recommendationLimit()).isEqualTo(3);
        assertThat(response.recommendedIds()).containsExactly(id(1), id(2), id(3));
        assertThat(response.ranking()).hasSize(5);
        assertThat(response.ranking().get(0).reasons().get(0).distanceMeters()).isEqualTo(200);
        assertThat(response.status()).isEqualTo("OK");
        assertThat(provider.requests).hasSize(5); // one multi-category batch per candidate, no per-keyword fan-out
        assertThat(response.sources()).extracting(Source::attribution).anyMatch(a -> a.contains("OpenStreetMap"));
    }

    @Test
    void twoCandidatesReturnExactlyTwoAndOriginalPositionsAreKept() {
        listings(2);
        Request request = new Request(List.of(id(2), id(1)), List.of(), List.of("SUPERMARKET"), null, null, Map.of(id(2), 2, id(1), 1));
        Response response = service(supermarkets(), "k", 100, 2, 24).compare(request);
        assertThat(response.recommendedIds()).containsExactly(id(1), id(2));
        assertThat(response.ranking()).extracting(RankedListing::originalPosition).containsExactly(1, 2);
        assertThat(response.candidates()).extracting(CandidateEvidence::originalPosition).containsExactly(2, 1);
    }

    @Test
    void invalidRequestsAreRejectedRatherThanClamped() {
        listings(3);
        SurroundingsService service = service(supermarkets(), "k", 100, 2, 24);
        assertThatThrownBy(() -> service.compare(request(11, "SUPERMARKET"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(new Request(List.of("not-a-uuid"), List.of(), List.of(), null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(new Request(List.of(id(1)), List.of("ALIEN"), List.of(), null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(new Request(List.of(id(1)), List.of(), List.of("BOGUS"), null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(new Request(List.of(id(1)), List.of(), List.of(), 0, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(new Request(List.of(id(1)), List.of(), List.of(), 10001, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(new Request(List.of(), List.of(), List.of(), null, null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultRadiusIs3000AndExplicitRadiusIsUsedAndCachedSeparately() {
        listings(1);
        Provider provider = supermarkets();
        SurroundingsService service = service(provider, "k", 100, 2, 24);
        assertThat(service.compare(request(1, "SUPERMARKET")).radiusMeters()).isEqualTo(3000);
        assertThat(provider.requests.get(0).get("filter")).endsWith(",3000");
        assertThat(provider.requests.get(0).get("filter")).startsWith("circle:106.7"); // longitude first
        Response second = service.compare(new Request(List.of(id(1)), List.of(), List.of("SUPERMARKET"), 5000, null, null));
        assertThat(second.radiusMeters()).isEqualTo(5000);
        assertThat(provider.requests).hasSize(2); // 3 km cache must not serve 5 km
        assertThat(provider.requests.get(1).get("filter")).endsWith(",5000");
    }

    @Test
    void secondIdenticalComparisonIsServedFromCacheWithoutOutboundCalls() {
        listings(2);
        Provider provider = supermarkets();
        SurroundingsService service = service(provider, "k", 100, 2, 24);
        service.compare(request(2, "SUPERMARKET"));
        int before = provider.requests.size();
        Response again = service.compare(request(2, "SUPERMARKET"));
        assertThat(provider.requests).hasSize(before);
        assertThat(again.usage().cacheHits()).isEqualTo(2);
        assertThat(again.candidates()).extracting(CandidateEvidence::cache).containsOnly("HIT");
    }

    @Test
    void blankKeyNeverCallsTheProviderAndNeverClaimsAnAmenityTop3() {
        listings(3);
        Provider provider = supermarkets();
        Response response = service(provider, "", 100, 2, 24).compare(request(3, "SUPERMARKET"));
        assertThat(provider.requests).isEmpty();
        assertThat(response.status()).isEqualTo("GEOAPIFY_NOT_CONFIGURED");
        assertThat(response.recommendedIds()).isEmpty();
        assertThat(response.candidates()).extracting(CandidateEvidence::status).containsOnly("NOT_CHECKED");
        assertThat(response.warnings()).isNotEmpty();
    }

    @Test
    void hiddenAndCoordinateLessListingsAreNeverRecommended() {
        listings(2);
        rows.put(id(3), new SurroundingsService.Row(id(3), false, 10.9, 106.7));
        rows.put(id(4), new SurroundingsService.Row(id(4), true, null, null));
        rows.put(id(5), new SurroundingsService.Row(id(5), true, 0.0, 0.0));
        Response response = service(supermarkets(), "k", 100, 2, 24).compare(new Request(
                List.of(id(1), id(2), id(3), id(4), id(5), id(6)), List.of(), List.of("SUPERMARKET"), null, null, null));
        Map<String, String> statuses = new HashMap<>();
        response.candidates().forEach(c -> statuses.put(c.listingId(), c.status()));
        assertThat(statuses.get(id(3))).isEqualTo("NOT_AVAILABLE");
        assertThat(statuses.get(id(4))).isEqualTo("MISSING_COORDINATES");
        assertThat(statuses.get(id(5))).isEqualTo("INVALID_COORDINATES");
        assertThat(statuses.get(id(6))).isEqualTo("NOT_AVAILABLE");
        assertThat(response.recommendedIds()).containsExactly(id(1), id(2));
        assertThat(response.status()).isEqualTo("PARTIAL");
    }

    @Test
    void offsetFollowsTheActualPageSizeAndTruncationIsReported() {
        listings(1);
        Provider provider = new Provider();
        provider.answer = q -> collection( // always a full page of restaurants, never the supermarket
                feature("a" + q.get("offset"), "Quán A", 10.81, 106.7, "catering.restaurant"),
                feature("b" + q.get("offset"), "Quán B", 10.811, 106.7, "catering.restaurant"));
        Response response = service(provider, "k", 2, 2, 24).compare(request(1, "SUPERMARKET", "FOOD"));
        List<String> offsets = provider.requests.stream().filter(q -> q.get("categories").contains(",")).map(q -> q.get("offset")).toList();
        assertThat(offsets).containsExactly("0", "2"); // offset advances by the real page size, not a hard-coded 100
        CandidateEvidence candidate = response.candidates().get(0);
        assertThat(candidate.paginationComplete()).isFalse();
        assertThat(candidate.status()).isEqualTo("PARTIAL");
        CategoryEvidence food = candidate.categories().stream().filter(c -> c.category().equals("FOOD")).findFirst().orElseThrow();
        assertThat(food.truncated()).isTrue();
        assertThat(candidate.warnings()).isNotEmpty();
    }

    @Test
    void aPageFullOfRestaurantsDoesNotMeanThereIsNoHospital() {
        listings(1);
        Provider provider = new Provider();
        provider.answer = q -> {
            String categories = q.get("categories");
            if (categories.equals("healthcare.hospital"))
                return collection(feature("h1", "Bệnh viện X", 10.8105, 106.7, "healthcare.hospital")); // short page => complete
            return collection(feature("a", "Quán A", 10.81, 106.7, "catering.restaurant"), feature("b", "Quán B", 10.811, 106.7, "catering.restaurant"));
        };
        Response response = service(provider, "k", 2, 1, 24).compare(request(1, "FOOD", "HOSPITAL"));
        CategoryEvidence hospital = response.candidates().get(0).categories().stream().filter(c -> c.category().equals("HOSPITAL")).findFirst().orElseThrow();
        assertThat(hospital.coverage()).isEqualTo("CHECKED");
        assertThat(hospital.uniqueCount()).isEqualTo(1);
        assertThat(hospital.nearest().get(0).name()).isEqualTo("Bệnh viện X");
    }

    @Test
    void exhaustedBudgetIsPartialAndNeverComparesScannedAgainstUnscannedRooms() {
        listings(5);
        Provider provider = supermarkets();
        Response response = service(provider, "k", 100, 2, 2).compare(request(5, "SUPERMARKET"));
        assertThat(provider.requests).hasSize(2);
        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.recommendedIds()).isEmpty();
        assertThat(response.warnings()).anyMatch(w -> w.contains("giới hạn") || w.contains("chưa thể xếp hạng"));
    }

    @Test
    void emptyProviderResultIsDistinctFromAnError() {
        listings(1);
        Provider provider = new Provider();
        provider.answer = q -> collection();
        Response response = service(provider, "k", 100, 2, 24).compare(request(1, "SUPERMARKET"));
        assertThat(response.candidates().get(0).status()).isEqualTo("EMPTY_PROVIDER_RESULT");
        assertThat(response.recommendedIds()).isEmpty(); // no amenity evidence is not a recommendation
        Provider broken = new Provider();
        broken.answer = q -> "{\"nope\":true}";
        Response bad = service(broken, "k", 100, 2, 24).compare(request(1, "SUPERMARKET"));
        assertThat(bad.candidates().get(0).status()).isEqualTo("PROVIDER_ERROR");
        assertThat(bad.status()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    void studentTargetIsConfirmedOnlyForAnExactNamedMatchAndUnnamedPlacesNeverMatch() {
        listings(1);
        Provider provider = new Provider();
        provider.answer = q -> collection(
                feature("u1", "Trường Đại học Công nghiệp Thành phố Hồ Chí Minh", 10.8105, 106.7, "education.university"),
                feature("u2", null, 10.8106, 106.7, "education.university"));
        Request request = new Request(List.of(id(1)), List.of("STUDENT"), List.of(), null, "Đại học Công nghiệp", null);
        Response confirmed = service(provider, "k", 100, 2, 24).compare(request);
        assertThat(confirmed.target().status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.target().options()).hasSize(1);
        assertThat(confirmed.target().options().get(0).name()).contains("Công nghiệp");
    }

    @Test
    void ambiguousOrAbbreviatedTargetNeedsConfirmationInsteadOfPickingTheFirstPoi() {
        listings(1);
        Provider provider = new Provider();
        provider.answer = q -> collection(
                feature("v1", "Trường Đại học Văn Lang", 10.8105, 106.7, "education.university"),
                feature("v2", "Trường Đại Học Văn Lang - Cơ Sở 2", 10.812, 106.7, "education.university"));
        Response ambiguous = service(provider, "k", 100, 2, 24).compare(
                new Request(List.of(id(1)), List.of("STUDENT"), List.of(), null, "Văn Lang", null));
        assertThat(ambiguous.target().status()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(ambiguous.target().options()).hasSize(2);
        Response missing = service(provider, "k", 100, 2, 24).compare(
                new Request(List.of(id(1)), List.of("STUDENT"), List.of(), null, "IUH cơ sở Nguyễn Văn Bảo", null));
        assertThat(missing.target().status()).isIn("NOT_FOUND", "NEEDS_CONFIRMATION");
        assertThat(missing.target().status()).isNotEqualTo("CONFIRMED");
    }

    @Test
    void presetsScanOnlyAllowlistedCategoriesInOneRequest() {
        listings(1);
        Provider provider = new Provider();
        provider.answer = q -> collection();
        service(provider, "k", 100, 2, 24).compare(new Request(List.of(id(1)), List.of("REMOTE_WORKER"), List.of(), null, null, null));
        assertThat(provider.requests).hasSize(1);
        String categories = provider.requests.get(0).get("categories");
        assertThat(categories).contains("commercial.convenience").contains("sport.fitness.gym").contains("sport.fitness.fitness_centre")
                .contains("leisure.park").contains("commercial.supermarket");
        assertThat(provider.requests.get(0)).doesNotContainKeys("text", "focus", "layers");
    }
}
