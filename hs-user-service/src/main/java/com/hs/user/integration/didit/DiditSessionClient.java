package com.hs.user.integration.didit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.config.didit.DiditProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiditSessionClient {

    private final DiditProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DiditSessionCreated createSession(String homespaceUserId) {
        if (!properties.isSessionApiConfigured()) {
            throw new AppException(UserErrorCode.KYC_NOT_CONFIGURED);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflow_id", properties.workflowId());
        body.put("vendor_data", homespaceUserId);
        body.put("language", "vi");

        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.resolvedBaseUrl() + "/v3/session/"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Didit create session failed: status={} bodyLength={}",
                        response.statusCode(),
                        response.body() == null ? 0 : response.body().length());
                throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
            }

            JsonNode root = objectMapper.readTree(response.body());
            String sessionId = text(root, "session_id");
            String url = text(root, "url");
            if (sessionId == null || url == null) {
                throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
            }
            return new DiditSessionCreated(
                    sessionId,
                    url,
                    text(root, "status"),
                    text(root, "workflow_id"),
                    text(root, "vendor_data")
            );
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Didit create session error: {}", ex.toString());
            throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public record DiditSessionCreated(
            String sessionId,
            String url,
            String status,
            String workflowId,
            String vendorData
    ) {}
}
