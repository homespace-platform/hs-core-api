package com.hs.user.integration.vnpt;

import com.hs.user.config.vnpt.VnptKycProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VnptKycPropertiesTest {
    @Test void dailyAttemptLimitDefaultsToDisabled() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("vnpt.ekyc.enabled", "true", "vnpt.ekyc.client-id", "id", "vnpt.ekyc.client-secret", "secret"));
        var properties = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("vnpt.ekyc", VnptKycProperties.class).get();
        assertEquals(0, properties.dailyAttempts());
        assertTrue(properties.configured());
        assertFalse(new VnptKycProperties(true, "https://example.test", "id", "secret", 15, -1, 1).configured());
    }
    @Test void onlyClientCredentialsAreRequiredForLevel2Authentication() {
        assertTrue(config("id", "secret").configured());
        assertFalse(config("id", "").configured());
        assertFalse(config("", "secret").configured());
    }
    @Test void enabledHttpsAndImageLimitsRemainRequired() {
        assertFalse(new VnptKycProperties(false, "https://example.test", "id", "secret", 15, 5, 1).configured());
        assertFalse(new VnptKycProperties(true, "http://example.test", "id", "secret", 15, 5, 1).configured());
        assertFalse(new VnptKycProperties(true, "https://example.test", "id", "secret", 15, 5, 6).configured());
    }
    private VnptKycProperties config(String id, String secret) {
        return new VnptKycProperties(true, "https://example.test", id, secret, 15, 5, 1);
    }
}
