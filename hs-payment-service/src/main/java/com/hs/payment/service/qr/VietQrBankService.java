package com.hs.payment.service.qr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.payment.dto.BankResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;

/** Cached, validated Vietnamese bank list from VietQR with a bundled fallback snapshot. */
@Slf4j
@Service
public class VietQrBankService {

    private static final Pattern BIN = Pattern.compile("^\\d{6}$");
    private static final int MAX_LEN = 120;
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private final RestTemplate client;
    private final Clock clock;
    private final String url;
    private final Duration ttl;
    private final Executor refresher;

    private List<BankResponse> banks; // guarded by this
    private Instant nextRefresh = Instant.MIN; // guarded by this
    private boolean refreshing; // guarded by this

    @Autowired
    public VietQrBankService(
            @Value("${homespace.payment.vietqr.banks-url:https://api.vietqr.io/v2/banks}") String url,
            @Value("${homespace.payment.vietqr.banks-cache-ttl:PT24H}") Duration ttl) {
        this(url, ttl, newClient(), Clock.systemUTC(), ForkJoinPool.commonPool());
    }

    VietQrBankService(String url, Duration ttl, RestTemplate client, Clock clock, Executor refresher) {
        this.url = url;
        this.ttl = ttl;
        this.client = client;
        this.clock = clock;
        this.refresher = refresher;
    }

    private static RestTemplate newClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    /**
     * Stale-while-revalidate: once a list exists, callers get it immediately and an expired cache is
     * refreshed in the background (one refresh at a time), so a slow/down VietQR never blocks requests.
     * Only the very first call waits for the upstream (bounded by the client timeouts).
     */
    public List<BankResponse> getBanks() {
        synchronized (this) {
            if (banks == null) {
                apply(fetch()); // cold start: single-flight under the lock
                return banks;
            }
            if (refreshing || clock.instant().isBefore(nextRefresh)) {
                return banks;
            }
            refreshing = true;
        }
        List<BankResponse> stale = banks;
        refresher.execute(() -> {
            List<BankResponse> fresh = fetch();
            synchronized (this) {
                apply(fresh);
                refreshing = false;
            }
        });
        return stale;
    }

    /** Fetches outside any lock; null means the refresh failed. */
    private List<BankResponse> fetch() {
        try {
            List<BankResponse> fresh = parse(client.getForObject(url, JsonNode.class));
            if (fresh.isEmpty()) {
                throw new IllegalStateException("empty bank list");
            }
            return fresh;
        } catch (Exception e) {
            log.warn("VietQR bank list refresh failed: {}", e.toString());
            return null;
        }
    }

    private void apply(List<BankResponse> fresh) { // caller holds the lock
        Instant now = clock.instant();
        if (fresh != null) {
            banks = fresh;
            nextRefresh = now.plus(ttl);
            return;
        }
        if (banks == null) {
            banks = loadFallback();
        }
        nextRefresh = now.plus(RETRY_AFTER_FAILURE);
    }

    static List<BankResponse> parse(JsonNode root) {
        Map<String, BankResponse> byBin = new LinkedHashMap<>();
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        for (JsonNode n : data) {
            if (n.path("transferSupported").asInt(0) != 1) continue;
            String bin = text(n, "bin");
            String code = text(n, "code");
            String name = text(n, "name");
            String shortName = text(n, "shortName");
            String logo = text(n, "logo");
            if (bin == null || !BIN.matcher(bin).matches() || code == null || name == null || shortName == null) continue;
            if (logo != null && !logo.startsWith("https://")) logo = null;
            byBin.putIfAbsent(bin, new BankResponse(bin, code, name, shortName, logo));
        }
        return List.copyOf(byBin.values());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) return null;
        String s = v.asText().trim();
        return s.isEmpty() || s.length() > MAX_LEN ? null : s;
    }

    private static List<BankResponse> loadFallback() {
        try (InputStream in = new ClassPathResource("vietqr/banks-fallback.json").getInputStream()) {
            // snapshot is pre-filtered; mark entries supported so it goes through the same validation
            var mapper = new ObjectMapper();
            var arr = mapper.createArrayNode();
            for (JsonNode n : mapper.readTree(in)) {
                arr.add(((ObjectNode) n).put("transferSupported", 1));
            }
            return parse(mapper.createObjectNode().set("data", arr));
        } catch (Exception e) {
            throw new IllegalStateException("bundled bank snapshot unreadable", e);
        }
    }
}
