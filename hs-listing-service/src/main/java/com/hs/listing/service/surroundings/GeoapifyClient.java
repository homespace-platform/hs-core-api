package com.hs.listing.service.surroundings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Geoapify Places adapter. Single endpoint {@code GET /v2/places}; a blank key never produces an outbound
 * request. The API key is never logged, returned or embedded in an exception message.
 */
@Component
public class GeoapifyClient {
    private static final Logger log = LoggerFactory.getLogger(GeoapifyClient.class);
    private static final int MAX_BODY_BYTES = 3_000_000;
    private static final int MAX_ATTEMPTS = 3;

    public record Query(List<String> categories, double latitude, double longitude, int radiusMeters, int limit, int offset) {}
    public record Response(int status, byte[] body, Integer retryAfterSeconds) {}

    @FunctionalInterface
    interface Transport { Response get(URI uri, Duration timeout) throws Exception; }

    private final String apiKey;
    private final String baseUrl;
    private final Duration deadline;
    private final Transport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GeoapifyClient(@Value("${geoapify.api-key:}") String apiKey,
                          @Value("${geoapify.base-url:https://api.geoapify.com}") String baseUrl) {
        this(apiKey, baseUrl, Duration.ofSeconds(12), defaultTransport());
    }

    GeoapifyClient(String apiKey, String baseUrl, Duration deadline, Transport transport) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.geoapify.com" : baseUrl.strip();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.deadline = deadline;
        this.transport = transport;
    }

    public boolean configured() { return !apiKey.isBlank(); }

    private static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) throw new GeoapifyException(GeoapifyException.Code.INVALID_RESPONSE, "Geoapify response too large");
                Integer retryAfter = response.headers().firstValue("Retry-After").map(GeoapifyClient::seconds).orElse(null);
                return new Response(response.statusCode(), bytes, retryAfter);
            }
        };
    }

    private static Integer seconds(String value) {
        try { return Math.max(0, Integer.parseInt(value.strip())); } catch (NumberFormatException ignored) { return null; }
    }

    /** Longitude first in circle/proximity; categories come only from the backend allowlist. */
    JsonNode places(Query query) {
        if (!configured()) {
            throw new GeoapifyException(GeoapifyException.Code.GEOAPIFY_NOT_CONFIGURED, "Geoapify API key is not configured");
        }
        URI uri = uri(query);
        long end = System.nanoTime() + deadline.toNanos();
        GeoapifyException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = System.nanoTime();
            try {
                Duration remaining = Duration.ofNanos(Math.max(1, end - started));
                Response response = transport.get(uri, remaining.compareTo(Duration.ofSeconds(8)) > 0 ? Duration.ofSeconds(8) : remaining);
                log.info("geoapify places status={} latencyMs={} attempt={} categories={}", response.status(),
                        (System.nanoTime() - started) / 1_000_000, attempt, query.categories().size());
                int status = response.status();
                if (status == 200) return parse(response.body());
                if (status == 401 || status == 403) throw new GeoapifyException(GeoapifyException.Code.UPSTREAM_AUTH, "Geoapify credential or permission error");
                if (status == 429) {
                    Integer wait = response.retryAfterSeconds();
                    if (wait != null && wait <= 2 && System.nanoTime() + wait * 1_000_000_000L < end && attempt < MAX_ATTEMPTS) {
                        sleep(wait * 1000L);
                        last = new GeoapifyException(GeoapifyException.Code.UPSTREAM_RATE_LIMITED, "Geoapify rate limited", wait);
                        continue;
                    }
                    throw new GeoapifyException(GeoapifyException.Code.UPSTREAM_RATE_LIMITED, "Geoapify rate limited", wait);
                }
                if (status >= 500) { last = new GeoapifyException(GeoapifyException.Code.UPSTREAM_ERROR, "Geoapify server error " + status); }
                else { throw new GeoapifyException(GeoapifyException.Code.UPSTREAM_ERROR, "Geoapify rejected the request (" + status + ")"); } // no blind retry
            } catch (GeoapifyException ex) {
                throw ex;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new GeoapifyException(GeoapifyException.Code.UPSTREAM_ERROR, "Interrupted while calling Geoapify");
            } catch (Exception ex) {
                last = new GeoapifyException(GeoapifyException.Code.UPSTREAM_ERROR, "Geoapify request failed: " + ex.getClass().getSimpleName());
            }
            if (attempt < MAX_ATTEMPTS && System.nanoTime() + 300_000_000L < end) sleep(200L * attempt);
            else break;
        }
        throw last != null ? last : new GeoapifyException(GeoapifyException.Code.UPSTREAM_ERROR, "Geoapify unavailable");
    }

    private JsonNode parse(byte[] body) {
        try { return mapper.readTree(body); }
        catch (Exception ex) { throw new GeoapifyException(GeoapifyException.Code.INVALID_RESPONSE, "Geoapify returned invalid JSON"); }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    URI uri(Query q) {
        String lon = plain(q.longitude()), lat = plain(q.latitude());
        String categories = String.join(",", q.categories());
        String query = "categories=" + enc(categories)
                + "&filter=" + enc("circle:" + lon + "," + lat + "," + q.radiusMeters())
                + "&bias=" + enc("proximity:" + lon + "," + lat)
                + "&limit=" + q.limit() + "&offset=" + q.offset() + "&lang=vi&apiKey=" + enc(apiKey);
        return URI.create(baseUrl + "/v2/places?" + query);
    }

    private static String plain(double value) { return String.format(Locale.ROOT, "%.7f", value); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
