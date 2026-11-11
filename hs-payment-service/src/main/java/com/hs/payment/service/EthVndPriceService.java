package com.hs.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

@Service
public class EthVndPriceService {
    private static final URI PRICE_URI = URI.create("https://api.coingecko.com/api/v3/simple/price?ids=ethereum&vs_currencies=vnd&include_last_updated_at=true");
    private static final BigDecimal WEI_PER_ETH = new BigDecimal("1000000000000000000");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;

    public EthVndPriceService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Price currentPrice(Instant now) {
        try {
            HttpRequest request = HttpRequest.newBuilder(PRICE_URI).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("CoinGecko returned " + response.statusCode());
            return parse(mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(response.body()), now);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AppException(PaymentErrorCode.ETH_PRICE_UNAVAILABLE);
        }
    }

    public static Price parse(JsonNode body, Instant now) {
        JsonNode eth = body.path("ethereum");
        JsonNode value = eth.path("vnd");
        JsonNode updated = eth.path("last_updated_at");
        if (!value.isNumber() || !updated.isIntegralNumber() || !updated.canConvertToLong())
            throw new AppException(PaymentErrorCode.ETH_PRICE_UNAVAILABLE);
        BigDecimal vnd = value.decimalValue();
        Instant updatedAt = Instant.ofEpochSecond(updated.longValue());
        if (vnd.signum() <= 0 || updatedAt.isAfter(now.plusSeconds(30)) || updatedAt.isBefore(now.minus(Duration.ofMinutes(5))))
            throw new AppException(PaymentErrorCode.ETH_PRICE_UNAVAILABLE);
        return new Price(vnd, updatedAt);
    }

    public static BigInteger weiFor(BigDecimal amountVnd, BigDecimal ethVnd) {
        if (amountVnd == null || amountVnd.signum() <= 0 || ethVnd == null || ethVnd.signum() <= 0)
            throw new IllegalArgumentException("Positive VND amount and ETH/VND price required");
        return amountVnd.multiply(WEI_PER_ETH).divide(ethVnd, 0, RoundingMode.CEILING).toBigIntegerExact();
    }

    public record Price(BigDecimal ethVnd, Instant updatedAt) { }
}
