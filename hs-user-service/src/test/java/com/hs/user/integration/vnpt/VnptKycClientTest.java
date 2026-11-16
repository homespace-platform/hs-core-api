package com.hs.user.integration.vnpt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.user.config.vnpt.VnptKycProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.hs.common.advice.entity.AppException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VnptKycClientTest {
    @Test void refreshesOn401ButDoesNotReplayOtherFailures() throws Exception {
        for (int failure : List.of(401, 503)) {
            AtomicInteger tokenCalls = new AtomicInteger(), sdkCalls = new AtomicInteger();
            List<String> authorizations = new ArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/auth/oauth/token", exchange -> {
                byte[] bytes = ("{\"access_token\":\"token-" + tokenCalls.incrementAndGet() + "\",\"expires_in\":100}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.createContext("/verify", exchange -> {
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                int status = sdkCalls.incrementAndGet() == 1 ? failure : 200;
                byte[] bytes = "{\"message\":\"IDG-00000000\",\"object\":{}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            try {
                var config = oauthConfig(server);
                var mapper = new ObjectMapper();
                var client = new VnptKycClient(config, mapper, new VnptAccessTokenProvider(config, mapper));
                if (failure == 401) {
                    assertEquals("IDG-00000000", client.json("/verify", mapper.createObjectNode()).path("message").asText());
                    assertEquals(List.of("Bearer token-1", "Bearer token-2"), authorizations);
                    assertEquals(2, tokenCalls.get());assertEquals(2, sdkCalls.get());
                } else {
                    assertThrows(AppException.class, () -> client.json("/verify", mapper.createObjectNode()));
                    assertEquals(1, tokenCalls.get());assertEquals(1, sdkCalls.get());
                }
            } finally { server.stop(0); }
        }
    }

    @Test void stopsAfterASecond401() throws Exception {
        AtomicInteger sdkCalls = new AtomicInteger(), tokenCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/oauth/token", exchange -> {
            tokenCalls.incrementAndGet();
            byte[] bytes = "{\"access_token\":\"rejected-token\",\"expires_in\":100}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/verify", exchange -> {
            sdkCalls.incrementAndGet();exchange.sendResponseHeaders(401, -1);exchange.close();
        });
        server.start();
        try {
            var config = oauthConfig(server);var mapper = new ObjectMapper();
            var client = new VnptKycClient(config, mapper, new VnptAccessTokenProvider(config, mapper));
            assertThrows(AppException.class, () -> client.json("/verify", mapper.createObjectNode()));
            assertEquals(2, tokenCalls.get());assertEquals(2, sdkCalls.get());
        } finally { server.stop(0); }
    }

    private VnptKycProperties oauthConfig(HttpServer server) {
        var config = mock(VnptKycProperties.class);
        when(config.configured()).thenReturn(true);
        when(config.baseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(config.clientId()).thenReturn("test-client");when(config.clientSecret()).thenReturn("test-secret");
        return config;
    }

    @Test void usesTheSdkChallengeCodeForJsonAndFileRequests() throws Exception {
        List<URI> requests=new ArrayList<>();
        List<String> authorizations=new ArrayList<>();
        List<Boolean> legacyHeaders=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.add(exchange.getRequestURI());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            legacyHeaders.add(exchange.getRequestHeaders().containsKey("Token-id") || exchange.getRequestHeaders().containsKey("Token-key"));
            byte[] body="{\"message\":\"IDG-00000000\",\"object\":{\"hash\":\"test\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();
        try {
            var config=mock(VnptKycProperties.class);
            when(config.configured()).thenReturn(true);
            when(config.baseUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            var tokens=mock(VnptAccessTokenProvider.class);
            when(tokens.accessToken()).thenReturn("issued-token");
            var client=new VnptKycClient(config,new ObjectMapper(),tokens);
            client.json("/ai/v1/web/ocr/id",new ObjectMapper().createObjectNode());
            client.upload(new byte[]{1},"text");
            assertEquals(2,requests.size());
            assertEquals("/ai/v1/web/ocr/id",requests.get(0).getPath());
            assertEquals("/file-service/v1/addFile",requests.get(1).getPath());
            assertTrue(requests.stream().allMatch(uri->"challengeCode=00000".equals(uri.getQuery())));
            assertEquals(List.of("Bearer issued-token","Bearer issued-token"),authorizations);
            assertEquals(List.of(false,false),legacyHeaders);
        } finally {server.stop(0);}
    }
}
