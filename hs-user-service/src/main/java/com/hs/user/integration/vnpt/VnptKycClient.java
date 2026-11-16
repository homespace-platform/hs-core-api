package com.hs.user.integration.vnpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.config.vnpt.VnptKycProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component @RequiredArgsConstructor
public class VnptKycClient {
    private static final Logger log = LoggerFactory.getLogger(VnptKycClient.class);
    private final VnptKycProperties config;
    private final ObjectMapper mapper;
    private final VnptAccessTokenProvider tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public JsonNode json(String endpoint, JsonNode body) {
        try { return send(endpoint, "application/json", mapper.writeValueAsBytes(body)); }
        catch (AppException ex) { throw ex; }
        catch (Exception ex) { throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR); }
    }
    public JsonNode upload(byte[] image, String contentType) {
        String boundary = "homespace-" + UUID.randomUUID();
        byte[] prefix = ("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"capture\"\r\nContent-Type: "+contentType+"\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\ncapture\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\nidentity-verification\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length+image.length+suffix.length];
        System.arraycopy(prefix,0,body,0,prefix.length); System.arraycopy(image,0,body,prefix.length,image.length); System.arraycopy(suffix,0,body,prefix.length+image.length,suffix.length);
        return send("/file-service/v1/addFile", "multipart/form-data; boundary="+boundary, body);
    }
    private JsonNode send(String endpoint, String type, byte[] body) {
        if (!config.configured()) throw new AppException(UserErrorCode.KYC_NOT_CONFIGURED);
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String token = tokens.accessToken();
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "")+endpoint+"?challengeCode=00000"))
                    .timeout(Duration.ofSeconds(45)).header("Authorization", "Bearer "+token)
                    .header("mac-address","WEB-001").header("Content-Type",type)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                HttpResponse<java.io.InputStream> response = http.send(request,HttpResponse.BodyHandlers.ofInputStream());
                try (var stream=response.body()) {
                    byte[] bytes=stream.readNBytes(262145);
                    if (response.statusCode() == 401) {
                        tokens.invalidate(token);
                        if (attempt == 0) continue;
                    }
                    if (response.statusCode()<200 || response.statusCode()>=300 || bytes.length>262144) {
                        log.warn("VNPT {} returned HTTP {}, response bytes {}", endpoint, response.statusCode(), bytes.length);
                        throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
                    }
                    JsonNode result=mapper.readTree(bytes);
                    if (result == null || !result.isObject()) {
                        log.warn("VNPT {} returned a non-object response", endpoint);
                        throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
                    }
                    if (result.path("object").isTextual()) ((com.fasterxml.jackson.databind.node.ObjectNode)result).set("object",mapper.readTree(result.path("object").asText()));
                    return result;
                }
            }
            throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); log.warn("VNPT {} request interrupted", endpoint); throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR); }
        catch (AppException ex) { throw ex; }
        catch (Exception ex) { log.warn("VNPT {} request failed: {}", endpoint, ex.getClass().getSimpleName()); throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR); }
    }
}
