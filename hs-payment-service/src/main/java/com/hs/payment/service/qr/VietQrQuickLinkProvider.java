package com.hs.payment.service.qr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Pattern;

@Slf4j
@Service
public class VietQrQuickLinkProvider implements VietQrProvider {

    @Value("${homespace.payment.vietqr.base-url:https://img.vietqr.io/image}")
    private String baseUrl;

    @Value("${homespace.payment.vietqr.template:compact2}")
    private String template;

    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    @Override
    public String generateQrImageUrl(
            String bankBinOrCode,
            String accountNumber,
            String accountHolderName,
            BigDecimal amount,
            String transferReference
    ) {
        String cleanBank = bankBinOrCode != null ? bankBinOrCode.trim() : "";
        String cleanAccount = accountNumber != null ? accountNumber.trim() : "";
        String cleanTemplate = template != null && !template.isBlank() ? template.trim() : "compact2";

        // Remove accents and keep ASCII alphanumeric
        String cleanDesc = sanitizeToAscii(transferReference);
        String cleanHolder = sanitizeToAscii(accountHolderName).toUpperCase();

        long amountLong = amount != null ? amount.longValue() : 0L;

        StringBuilder sb = new StringBuilder();
        sb.append(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        sb.append("/").append(urlEncode(cleanBank));
        sb.append("-").append(urlEncode(cleanAccount));
        sb.append("-").append(urlEncode(cleanTemplate)).append(".png");

        sb.append("?amount=").append(amountLong);
        if (!cleanDesc.isEmpty()) {
            sb.append("&addInfo=").append(urlEncode(cleanDesc));
        }
        if (!cleanHolder.isEmpty()) {
            sb.append("&accountName=").append(urlEncode(cleanHolder));
        }

        return sb.toString();
    }

    public static String sanitizeToAscii(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        String noDiacritics = DIACRITICS_PATTERN.matcher(normalized).replaceAll("");
        // Replace special Vietnamese characters
        String replaced = noDiacritics.replace("đ", "d").replace("Đ", "D");
        // Keep only alphanumeric and whitespace
        return replaced.replaceAll("[^a-zA-Z0-9 ]", "").trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
