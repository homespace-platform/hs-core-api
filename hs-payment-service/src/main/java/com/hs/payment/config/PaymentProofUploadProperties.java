package com.hs.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình public base URL cho phiên tải chứng từ mobile handoff.
 * Biến môi trường tương ứng: PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL
 */
@ConfigurationProperties(prefix = "homespace.payment.proof-upload")
public record PaymentProofUploadProperties(
        String publicBaseUrl
) {
}
