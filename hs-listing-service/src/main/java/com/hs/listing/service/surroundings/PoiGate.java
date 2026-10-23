package com.hs.listing.service.surroundings;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared outbound limiter for every instance (Redis fixed-window counter) plus bounded concurrency.
 * When Redis is configured but fails, the request is DENIED rather than silently unlimited. HomeSpace keeps a
 * conservative 45 requests / 60 s policy; this is not a statement of Geoapify's official quota.
 */
@Component
public class PoiGate {
    private static final Logger log = LoggerFactory.getLogger(PoiGate.class);

    /** Atomic increment returning the new count; implementations set the expiry on first increment. */
    @FunctionalInterface
    public interface Counter { long increment(String key, Duration ttl); }

    private final Counter counter;
    private final int limit;
    private final long windowSeconds;
    private final LongSupplier clockSeconds;
    private final Semaphore inflight;

    @Autowired
    public PoiGate(ObjectProvider<StringRedisTemplate> redis,
                   @Value("${geoapify.rate-limit.requests:45}") int limit,
                   @Value("${geoapify.rate-limit.window-seconds:60}") int windowSeconds,
                   @Value("${geoapify.max-concurrency:4}") int concurrency) {
        this(redisCounter(redis.getIfAvailable()), limit, windowSeconds, concurrency, () -> System.currentTimeMillis() / 1000);
    }

    PoiGate(Counter counter, int limit, int windowSeconds, int concurrency, LongSupplier clockSeconds) {
        this.counter = counter;
        this.limit = Math.max(1, limit);
        this.windowSeconds = Math.max(1, windowSeconds);
        this.clockSeconds = clockSeconds;
        this.inflight = new Semaphore(Math.max(1, concurrency));
    }

    private static Counter redisCounter(StringRedisTemplate redis) {
        if (redis == null) return localCounter();
        return (key, ttl) -> {
            Long value = redis.opsForValue().increment(key);
            if (value == null) throw new IllegalStateException("Redis returned no counter");
            if (value == 1L) redis.expire(key, ttl);
            return value;
        };
    }

    private static Counter localCounter() {
        java.util.concurrent.ConcurrentHashMap<String, long[]> counts = new java.util.concurrent.ConcurrentHashMap<>();
        return (key, ttl) -> {
            long now = System.currentTimeMillis();
            counts.values().removeIf(entry -> entry[1] < now);
            long[] entry = counts.compute(key, (k, old) -> {
                if (old == null) return new long[] {1, now + ttl.toMillis()};
                old[0]++;
                return old;
            });
            return entry[0];
        };
    }

    /** True when an outbound request may be sent now. Never throws; a failing limiter means "no". */
    public boolean tryAcquire() {
        try {
            long window = clockSeconds.getAsLong() / windowSeconds;
            return counter.increment("geoapify:places:" + window, Duration.ofSeconds(windowSeconds * 2)) <= limit;
        } catch (RuntimeException ex) {
            log.warn("Geoapify limiter unavailable; denying outbound request: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    /** Per-user abuse limit: a signed-in user cannot drive unlimited paid provider calls. */
    public boolean tryAcquireUser(String userId, int userLimit) {
        try {
            long window = clockSeconds.getAsLong() / windowSeconds;
            return counter.increment("geoapify:user:" + userId + ":" + window, Duration.ofSeconds(windowSeconds * 2)) <= userLimit;
        } catch (RuntimeException ex) {
            log.warn("Geoapify user limiter unavailable; denying request: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    public boolean enter() { return inflight.tryAcquire(); }
    public void leave() { inflight.release(); }
}
