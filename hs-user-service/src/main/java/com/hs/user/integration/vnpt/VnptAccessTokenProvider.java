package com.hs.user.integration.vnpt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.config.vnpt.VnptKycProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Objects;

@Component
public class VnptAccessTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(VnptAccessTokenProvider.class);
    private final VnptKycProperties config;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String cachedToken;
    private Instant refreshAt = Instant.MIN;

    @Autowired
    public VnptAccessTokenProvider(VnptKycProperties config, ObjectMapper mapper) {
        this(config, mapper, Clock.systemUTC());
    }

    VnptAccessTokenProvider(VnptKycProperties config, ObjectMapper mapper, Clock clock) {
        this.config = config;
        this.mapper = mapper;
        this.clock = clock;
    }

    public synchronized String accessToken() {
        if (!config.configured()) throw new AppException(UserErrorCode.KYC_NOT_CONFIGURED);
        Instant started = clock.instant();
        if (cachedToken != null && started.isBefore(refreshAt)) return cachedToken;
        try {
            var body = mapper.createObjectNode().put("client_id", config.clientId())
                    .put("client_secret", config.clientSecret()).put("grant_type", "client_credentials");
            var request = HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "") + "/auth/oauth/token"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(65537);
                if (response.statusCode() < 200 || response.statusCode() >= 300 || bytes.length > 65536) {
                    log.warn("VNPT token endpoint returned HTTP {}", response.statusCode());
                    throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
                }
                var result = mapper.readTree(bytes);
                var token = result == null ? null : result.get("access_token");
                var expiry = result == null ? null : result.get("expires_in");
                if (token == null || !token.isTextual() || token.asText().length() > 16000
                        || !token.asText().matches("[A-Za-z0-9._~+/-]+=*")
                        || expiry == null || !expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.asLong() <= 0)
                    throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
                long ttl = expiry.asLong();
                Instant usableUntil = started.plusSeconds(ttl - Math.min(60, ttl / 10));
                if (!clock.instant().isBefore(usableUntil)) throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
                cachedToken = token.asText();
                refreshAt = usableUntil;
                return cachedToken;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
        } catch (AppException ex) { throw ex; }
        catch (Exception ex) {
            log.warn("VNPT token request failed: {}", ex.getClass().getSimpleName());
            throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);
        }
    }

    public synchronized void invalidate(String rejectedToken) {
        // A delayed 401 must not discard a newer token obtained by another request.
        if (Objects.equals(cachedToken, rejectedToken)) {
            cachedToken = null;
            refreshAt = Instant.MIN;
        }
    }
}
