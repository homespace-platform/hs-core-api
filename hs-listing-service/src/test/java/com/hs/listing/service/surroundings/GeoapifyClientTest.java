package com.hs.listing.service.surroundings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GeoapifyClientTest {
    private static final String EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}";
    private static final GeoapifyClient.Query QUERY = new GeoapifyClient.Query(
            List.of("education.university", "commercial.supermarket"), 10.8336482, 106.6895746, 3000, 100, 100);

    private static GeoapifyClient.Response ok() { return new GeoapifyClient.Response(200, EMPTY.getBytes(StandardCharsets.UTF_8), null); }
    private static GeoapifyClient.Response status(int code, Integer retryAfter) { return new GeoapifyClient.Response(code, new byte[0], retryAfter); }

    @Test
    void blankKeySendsNothingAndReportsNotConfigured() {
        AtomicInteger calls = new AtomicInteger();
        GeoapifyClient client = new GeoapifyClient("  ", "https://api.geoapify.com", Duration.ofSeconds(5), (uri, t) -> { calls.incrementAndGet(); return ok(); });
        assertThat(client.configured()).isFalse();
        assertThatThrownBy(() -> client.places(QUERY)).isInstanceOfSatisfying(GeoapifyException.class,
                e -> assertThat(e.code()).isEqualTo(GeoapifyException.Code.GEOAPIFY_NOT_CONFIGURED));
        assertThat(calls).hasValue(0);
    }

    @Test
    void buildsPostmanContractWithLongitudeFirstAndNoVietmapParameters() {
        List<URI> seen = new ArrayList<>();
        GeoapifyClient client = new GeoapifyClient("SECRET", "https://api.geoapify.com/", Duration.ofSeconds(5), (uri, t) -> { seen.add(uri); return ok(); });
        client.places(QUERY);
        String raw = java.net.URLDecoder.decode(seen.get(0).toString(), StandardCharsets.UTF_8);
        assertThat(raw).startsWith("https://api.geoapify.com/v2/places?categories=education.university,commercial.supermarket");
        assertThat(raw).contains("filter=circle:106.6895746,10.8336482,3000").contains("bias=proximity:106.6895746,10.8336482")
                .contains("limit=100").contains("offset=100").contains("lang=vi").contains("apiKey=SECRET");
        assertThat(raw).doesNotContain("text=").doesNotContain("focus=").doesNotContain("circle_center").doesNotContain("layers=")
                .doesNotContain("display_type").doesNotContain("ref_id");
    }

    @Test
    void errorsNeverLeakTheKey() {
        GeoapifyClient client = new GeoapifyClient("TOPSECRET", "https://api.geoapify.com", Duration.ofSeconds(5), (uri, t) -> { throw new java.io.IOException(uri.toString()); });
        assertThatThrownBy(() -> client.places(QUERY)).isInstanceOf(GeoapifyException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("TOPSECRET"));
    }

    @Test
    void credentialErrorsAreConfigurationErrorsAndNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        GeoapifyClient client = new GeoapifyClient("k", "https://x.test", Duration.ofSeconds(5), (uri, t) -> { calls.incrementAndGet(); return status(401, null); });
        assertThatThrownBy(() -> client.places(QUERY)).isInstanceOfSatisfying(GeoapifyException.class,
                e -> assertThat(e.code()).isEqualTo(GeoapifyException.Code.UPSTREAM_AUTH));
        assertThat(calls).hasValue(1);
    }

    @Test
    void badRequestIsNotBlindlyRetried() {
        AtomicInteger calls = new AtomicInteger();
        GeoapifyClient client = new GeoapifyClient("k", "https://x.test", Duration.ofSeconds(5), (uri, t) -> { calls.incrementAndGet(); return status(400, null); });
        assertThatThrownBy(() -> client.places(QUERY)).isInstanceOf(GeoapifyException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void serverErrorsRetryAFiniteNumberOfTimesThenRecover() {
        AtomicInteger calls = new AtomicInteger();
        GeoapifyClient client = new GeoapifyClient("k", "https://x.test", Duration.ofSeconds(5), (uri, t) -> calls.incrementAndGet() < 3 ? status(503, null) : ok());
        assertThat(client.places(QUERY).path("type").asText()).isEqualTo("FeatureCollection");
        assertThat(calls).hasValue(3);
        AtomicInteger always = new AtomicInteger();
        GeoapifyClient failing = new GeoapifyClient("k", "https://x.test", Duration.ofSeconds(5), (uri, t) -> { always.incrementAndGet(); return status(500, null); });
        assertThatThrownBy(() -> failing.places(QUERY)).isInstanceOf(GeoapifyException.class);
        assertThat(always).hasValue(3);
    }

    @Test
    void rateLimitWithLongRetryAfterIsSurfacedInsteadOfWaiting() {
        GeoapifyClient client = new GeoapifyClient("k", "https://x.test", Duration.ofSeconds(5), (uri, t) -> status(429, 30));
        assertThatThrownBy(() -> client.places(QUERY)).isInstanceOfSatisfying(GeoapifyException.class, e -> {
            assertThat(e.code()).isEqualTo(GeoapifyException.Code.UPSTREAM_RATE_LIMITED);
            assertThat(e.retryAfterSeconds()).isEqualTo(30);
        });
    }

    @Test
    void invalidJsonIsInvalidResponse() {
        GeoapifyClient client = new GeoapifyClient("k", "https://x.test", Duration.ofSeconds(5),
                (uri, t) -> new GeoapifyClient.Response(200, "<html>".getBytes(StandardCharsets.UTF_8), null));
        assertThatThrownBy(() -> client.places(QUERY)).isInstanceOfSatisfying(GeoapifyException.class,
                e -> assertThat(e.code()).isEqualTo(GeoapifyException.Code.INVALID_RESPONSE));
    }
}
