package com.hs.user.config.vnpt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vnpt.ekyc")
public record VnptKycProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://api.idg.vnpt.vn") String baseUrl,
        String accessToken, String tokenId, String tokenKey,
        @DefaultValue("15") int sessionMinutes,
        @DefaultValue("5") int dailyAttempts,
        @DefaultValue("1") int maxImageMb) {
    public boolean configured() {
        return enabled && accessToken != null && !accessToken.isBlank()
                && tokenId != null && !tokenId.isBlank() && tokenKey != null && !tokenKey.isBlank()
                && baseUrl != null && baseUrl.startsWith("https://") && sessionMinutes > 0
                && dailyAttempts > 0 && maxImageMb > 0 && maxImageMb <= 5;
    }
}
