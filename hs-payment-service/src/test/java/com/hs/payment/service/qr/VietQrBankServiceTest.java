package com.hs.payment.service.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class VietQrBankServiceTest {
    static final String UPSTREAM = """
        {"code":"00","data":[
         {"name":"A Bank","code":"AAA","bin":"970415","shortName":"A","logo":"https://x/a.png","transferSupported":1},
         {"name":"A dup","code":"AAA2","bin":"970415","shortName":"A2","logo":"https://x/a2.png","transferSupported":1},
         {"name":"B Bank","code":"BBB","bin":"970416","shortName":"B","logo":"http://x/b.png","transferSupported":1},
         {"name":"No transfer","code":"CCC","bin":"970417","shortName":"C","transferSupported":0},
         {"name":"Bad bin","code":"DDD","bin":"12345","shortName":"D","transferSupported":1},
         {"name":" ","code":"EEE","bin":"970418","shortName":"E","transferSupported":1},
         {"name":"%s","code":"FFF","bin":"970419","shortName":"F","transferSupported":1}]}
        """.formatted("x".repeat(121));

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    final RestTemplate rest = mock(RestTemplate.class);
    final MutableClock clock = new MutableClock();
    final VietQrBankService svc = new VietQrBankService("http://stub", Duration.ofHours(24), rest, clock, Runnable::run);

    JsonNode json(String s) throws Exception { return new ObjectMapper().readTree(s); }

    @Test
    void filtersValidatesAndDedupes() throws Exception {
        when(rest.getForObject(anyString(), eq(JsonNode.class))).thenReturn(json(UPSTREAM));
        var banks = svc.getBanks();
        assertThat(banks).extracting("bin").containsExactly("970415", "970416");
        assertThat(banks.get(0).name()).isEqualTo("A Bank");
        assertThat(banks.get(1).logo()).isNull();
    }

    @Test
    void reusesCacheUntilTtlThenRefreshes() throws Exception {
        when(rest.getForObject(anyString(), eq(JsonNode.class))).thenReturn(json(UPSTREAM));
        svc.getBanks();
        svc.getBanks();
        verify(rest, times(1)).getForObject(anyString(), eq(JsonNode.class));
        clock.now = clock.now.plus(Duration.ofHours(25));
        svc.getBanks();
        verify(rest, times(2)).getForObject(anyString(), eq(JsonNode.class));
    }

    @Test
    void keepsLastGoodListWhenRefreshFails() throws Exception {
        when(rest.getForObject(anyString(), eq(JsonNode.class)))
                .thenReturn(json(UPSTREAM)).thenThrow(new RestClientException("down"));
        var first = svc.getBanks();
        clock.now = clock.now.plus(Duration.ofHours(25));
        assertThat(svc.getBanks()).isEqualTo(first);
    }

    @Test
    void servesBundledFallbackWhenNeverGood() {
        when(rest.getForObject(anyString(), eq(JsonNode.class))).thenThrow(new RestClientException("down"));
        assertThat(svc.getBanks()).hasSizeGreaterThan(40).allSatisfy(b -> assertThat(b.bin()).matches("\\d{6}"));
        // a null/empty upstream body also counts as failure
        var empty = new VietQrBankService("http://stub", Duration.ofHours(1), mock(RestTemplate.class), clock, Runnable::run);
        assertThat(empty.getBanks()).isNotEmpty();
    }

    @Test
    void expiredCacheReturnsStaleImmediatelyAndRefreshesInBackground() throws Exception {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        var async = new VietQrBankService("http://stub", Duration.ofHours(24), rest, clock, queued::add);
        when(rest.getForObject(anyString(), eq(JsonNode.class)))
                .thenReturn(json(UPSTREAM))
                .thenReturn(json("{\"data\":[{\"name\":\"Z\",\"code\":\"ZZZ\",\"bin\":\"970499\",\"shortName\":\"Z\",\"transferSupported\":1}]}"));
        var first = async.getBanks();
        clock.now = clock.now.plus(Duration.ofHours(25));
        assertThat(async.getBanks()).isEqualTo(first); // stale, not blocked
        assertThat(async.getBanks()).isEqualTo(first); // refresh already in flight: no second fetch
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        assertThat(async.getBanks()).extracting("bin").containsExactly("970499");
        verify(rest, times(2)).getForObject(anyString(), eq(JsonNode.class));
    }
}
