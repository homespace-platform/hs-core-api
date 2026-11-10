package com.hs.payment.membership;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class VnpaySigner {
    private final String secret;

    public VnpaySigner(@Value("${homespace.membership.vnpay.hash-secret:}") String secret) {
        this.secret = secret;
    }

    public String signedData(Map<String, String> values) {
        return new TreeMap<>(values).entrySet().stream()
                .filter(e -> e.getKey().startsWith("vnp_") && !e.getKey().equals("vnp_SecureHash")
                        && !e.getKey().equals("vnp_SecureHashType") && e.getValue() != null)
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    public String sign(Map<String, String> values) {
        if (secret.isBlank()) throw new IllegalStateException("VNPAY hash secret is not configured");
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(signedData(values).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign VNPAY request", e);
        }
    }

    public boolean verify(Map<String, String> values) {
        String supplied = values.get("vnp_SecureHash");
        if (supplied == null || !supplied.matches("(?i)[0-9a-f]{128}")) return false;
        return MessageDigest.isEqual(sign(values).getBytes(StandardCharsets.US_ASCII),
                supplied.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
