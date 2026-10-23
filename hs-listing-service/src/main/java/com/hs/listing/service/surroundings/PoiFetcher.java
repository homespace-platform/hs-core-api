package com.hs.listing.service.surroundings;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Cached, limited, budgeted access to one Geoapify page. Errors are never cached as empty results. */
@Component
public class PoiFetcher {
    /** Per-user-turn budget shared by every candidate so no room is scanned more than the others. */
    public static final class Turn {
        private final int maxRequests;
        private final long deadlineNanos;
        int providerRequests, cacheHits, pages;

        public Turn(int maxRequests, Duration deadline) {
            this.maxRequests = maxRequests;
            this.deadlineNanos = System.nanoTime() + deadline.toNanos();
        }

        boolean expired() { return System.nanoTime() > deadlineNanos; }
        boolean canRequest() { return providerRequests < maxRequests && !expired(); }
        public int providerRequests() { return providerRequests; }
        public int cacheHits() { return cacheHits; }
        public int pages() { return pages; }
    }

    public record Page(List<Poi> pois, int featureCount, int skipped, List<String> diagnostics, Instant fetchedAt, boolean fromCache) {}

    private record Entry(Page page, long expiresAtMillis) {}

    private static final int MAX_ENTRIES = 2000;
    private final GeoapifyClient client;
    private final PoiGate gate;
    private final int pageSize;
    private final Duration positiveTtl, emptyTtl;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public PoiFetcher(GeoapifyClient client, PoiGate gate,
                      @Value("${geoapify.page-size:100}") int pageSize,
                      @Value("${geoapify.cache.positive-ttl-seconds:86400}") long positiveTtlSeconds,
                      @Value("${geoapify.cache.empty-ttl-seconds:300}") long emptyTtlSeconds) {
        this.client = client;
        this.gate = gate;
        this.pageSize = Math.min(Math.max(pageSize, 1), 500);
        this.positiveTtl = Duration.ofSeconds(Math.max(0, positiveTtlSeconds));
        this.emptyTtl = Duration.ofSeconds(Math.max(0, emptyTtlSeconds));
    }

    public int pageSize() { return pageSize; }
    public boolean configured() { return client.configured(); }

    /** Cache key: provider/version + exact coordinates + radius + sorted categories + lang + limit/offset. */
    String key(double lat, double lon, int radius, List<String> categories, int offset) {
        return String.format(Locale.ROOT, "geoapify:v2:%.7f,%.7f:r%d:%s:vi:l%d:o%d", lat, lon, radius,
                String.join(",", categories.stream().sorted().toList()), pageSize, offset);
    }

    public Page page(Turn turn, double lat, double lon, int radius, List<String> categories, int offset) {
        if (!client.configured()) {
            throw new GeoapifyException(GeoapifyException.Code.GEOAPIFY_NOT_CONFIGURED, "Geoapify API key is not configured");
        }
        String key = key(lat, lon, radius, categories, offset);
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
            turn.cacheHits++;
            turn.pages++;
            Page page = cached.page();
            return new Page(page.pois(), page.featureCount(), page.skipped(), page.diagnostics(), page.fetchedAt(), true);
        }
        // Single flight: concurrent identical misses wait for the first request, then read its cache entry.
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                cached = cache.get(key);
                if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
                    turn.cacheHits++;
                    turn.pages++;
                    Page page = cached.page();
                    return new Page(page.pois(), page.featureCount(), page.skipped(), page.diagnostics(), page.fetchedAt(), true);
                }
                if (!turn.canRequest()) throw new GeoapifyException(GeoapifyException.Code.BUDGET_EXHAUSTED, "Per-turn Geoapify budget exhausted");
                if (!gate.tryAcquire()) throw new GeoapifyException(GeoapifyException.Code.BUDGET_EXHAUSTED, "Geoapify rate limit reached", 60);
                if (!gate.enter()) throw new GeoapifyException(GeoapifyException.Code.BUDGET_EXHAUSTED, "Too many concurrent Geoapify requests");
                JsonNode root;
                try {
                    turn.providerRequests++;
                    root = client.places(new GeoapifyClient.Query(categories, lat, lon, radius, pageSize, offset));
                } finally {
                    gate.leave();
                }
                PoiParser.ParseResult parsed = PoiParser.parse(root, lat, lon);
                Page page = new Page(parsed.pois(), parsed.featureCount(), parsed.skipped(), parsed.diagnostics(), Instant.now(), false);
                turn.pages++;
                store(key, page);
                return page;
            } finally {
                locks.remove(key, lock);
            }
        }
    }

    private void store(String key, Page page) {
        Duration ttl = page.featureCount() == 0 ? emptyTtl : positiveTtl;
        if (ttl.isZero()) return;
        if (cache.size() >= MAX_ENTRIES) cache.values().removeIf(entry -> entry.expiresAtMillis() <= System.currentTimeMillis());
        if (cache.size() >= MAX_ENTRIES) return; // bounded memory: skip caching rather than evict arbitrarily
        cache.put(key, new Entry(page, System.currentTimeMillis() + ttl.toMillis()));
    }
}
