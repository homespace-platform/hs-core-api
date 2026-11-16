package com.hs.user.integration.vnpt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.config.vnpt.VnptKycProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VnptAccessTokenProviderTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> response = new AtomicReference<>("{\"access_token\":\"issued-token\",\"expires_in\":100}");
    final AtomicReference<String> requestBody = new AtomicReference<>();
    final AtomicReference<String> contentType = new AtomicReference<>();
    final Clock clock = mock(Clock.class);
    final VnptKycProperties config = mock(VnptKycProperties.class);
    HttpServer server;
    VnptAccessTokenProvider provider;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/oauth/token", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        when(clock.instant()).thenReturn(Instant.EPOCH);
        when(config.configured()).thenReturn(true);
        when(config.baseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(config.clientId()).thenReturn("test-client");
        when(config.clientSecret()).thenReturn("test&secret*with\"quote");
        provider = new VnptAccessTokenProvider(config, mapper, clock);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void sendsJsonClientCredentialsAndReusesIssuedToken() throws Exception {
        assertEquals("issued-token", provider.accessToken());
        assertEquals("issued-token", provider.accessToken());
        assertEquals(1, calls.get());
        assertEquals("application/json", contentType.get());
        assertEquals(mapper.valueToTree(Map.of("client_id", "test-client", "client_secret", "test&secret*with\"quote",
                "grant_type", "client_credentials")), mapper.readTree(requestBody.get()));
    }

    @Test void refreshesBeforeExpiryAndDoesNotDiscardANewerTokenForAnOlder401() {
        assertEquals("issued-token", provider.accessToken());
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(89));
        assertEquals("issued-token", provider.accessToken());
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(90));
        response.set("{\"access_token\":\"new-token\",\"expires_in\":100}");
        assertEquals("new-token", provider.accessToken());
        provider.invalidate("issued-token");
        assertEquals("new-token", provider.accessToken());
        assertEquals(2, calls.get());
        provider.invalidate("new-token");
        provider.accessToken();
        assertEquals(3, calls.get());
    }

    @Test void concurrentRequestsShareOneTokenExchange() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> requests = Collections.nCopies(20, provider::accessToken);
            for (var result : executor.invokeAll(requests)) assertEquals("issued-token", result.get());
        }
        assertEquals(1, calls.get());
    }

    @Test void missingConfigurationNeverCallsTokenEndpoint() {
        when(config.configured()).thenReturn(false);
        assertThrows(AppException.class, provider::accessToken);
        assertEquals(0, calls.get());
    }

    @Test void invalidResponsesAreNeverCached() {
        for (String invalid : List.of("{}", "not-json", "[]",
                "{\"access_token\":\"token\",\"expires_in\":0}",
                "{\"access_token\":\"token\",\"expires_in\":\"100\"}",
                "{\"access_token\":\"Bearer token\",\"expires_in\":100}",
                "{\"access_token\":\"\",\"expires_in\":100}")) {
            response.set(invalid);
            assertThrows(AppException.class, provider::accessToken);
        }
        response.set("{\"access_token\":\"valid-token\",\"expires_in\":100}");
        assertEquals("valid-token", provider.accessToken());
        assertEquals(8, calls.get());
    }

    @Test void rejectedCredentialsFailWithoutReturningAToken() {
        status.set(401);
        assertThrows(AppException.class, provider::accessToken);
        assertEquals(1, calls.get());
    }
}
