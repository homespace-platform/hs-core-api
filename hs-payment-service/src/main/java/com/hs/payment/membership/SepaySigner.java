package com.hs.payment.membership;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class SepaySigner {
    private static final List<String> SIGNED_FIELDS = List.of(
            "order_amount", "merchant", "currency", "operation", "order_description",
            "order_invoice_number", "customer_id", "payment_method",
            "success_url", "error_url", "cancel_url");
    private final String secret;

    public SepaySigner(@Value("${homespace.membership.sepay.secret-key:}") String secret) {
        this.secret = secret;
    }

    public boolean isConfigured() {
        return secret != null && !secret.isBlank();
    }

    public String sign(Map<String, String> fields) {
        if (secret.isBlank()) throw new IllegalStateException("SePay merchant secret is not configured");
        List<String> parts = new ArrayList<>();
        for (String name : SIGNED_FIELDS) {
            String value = fields.get(name);
            if (value != null && !value.isBlank() && !"0".equals(value))
                parts.add(name + "=" + value);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(String.join(",", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign SePay checkout", e);
        }
    }
}
