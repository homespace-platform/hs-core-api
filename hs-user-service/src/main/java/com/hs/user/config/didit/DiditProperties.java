package com.hs.user.config.didit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Didit KYC settings — bind from {@code didit.*} / env {@code DIDIT_*}.
 * Secrets must never be exposed to frontend.
 */
@ConfigurationProperties(prefix = "didit")
public record DiditProperties(
        Api api,
        String workflowId,
        String webhookSecret
) {
    public record Api(
            String baseUrl,
            String key
    ) {}

    public String resolvedBaseUrl() {
        if (api == null || api.baseUrl() == null || api.baseUrl().isBlank()) {
            return "https://verification.didit.me";
        }
        return api.baseUrl().replaceAll("/+$", "");
    }

    public String apiKey() {
        return api == null ? null : api.key();
    }

    public boolean isSessionApiConfigured() {
        return notBlank(apiKey()) && notBlank(workflowId);
    }

    public boolean isWebhookConfigured() {
        return notBlank(webhookSecret)
                && !"PASTE_WEBHOOK_SIGNING_SECRET_HERE".equals(webhookSecret.trim());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
