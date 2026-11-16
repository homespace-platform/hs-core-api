package com.hs.user.integration.vnpt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.user.config.vnpt.VnptKycProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VnptKycClientTest {
    @Test void usesTheSdkChallengeCodeForJsonAndFileRequests() throws Exception {
        List<URI> requests=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.add(exchange.getRequestURI());
            byte[] body="{\"message\":\"IDG-00000000\",\"object\":{\"hash\":\"test\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();
        try {
            var config=mock(VnptKycProperties.class);
            when(config.configured()).thenReturn(true);
            when(config.baseUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(config.accessToken()).thenReturn("access");
            when(config.tokenId()).thenReturn("id");
            when(config.tokenKey()).thenReturn("key");
            var client=new VnptKycClient(config,new ObjectMapper());
            client.json("/ai/v1/web/ocr/id",new ObjectMapper().createObjectNode());
            client.upload(new byte[]{1},"text");
            assertEquals(2,requests.size());
            assertEquals("/ai/v1/web/ocr/id",requests.get(0).getPath());
            assertEquals("/file-service/v1/addFile",requests.get(1).getPath());
            assertTrue(requests.stream().allMatch(uri->"challengeCode=00000".equals(uri.getQuery())));
        } finally {server.stop(0);}
    }
}
