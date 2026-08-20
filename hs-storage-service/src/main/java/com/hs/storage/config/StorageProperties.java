package com.hs.storage.config;

import java.time.Duration;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "storage.s3")
@Validated
public record StorageProperties(
        @NotBlank String bucket,
        @NotBlank String region,
        String publicBaseUrl,
        Duration uploadUrlDuration,
        Duration downloadUrlDuration
) {
    public StorageProperties {
        publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank()
                ? "https://" + bucket + ".s3." + region + ".amazonaws.com"
                : publicBaseUrl.replaceAll("/+$", "");
        uploadUrlDuration = uploadUrlDuration == null ? Duration.ofMinutes(10) : uploadUrlDuration;
        downloadUrlDuration = downloadUrlDuration == null ? Duration.ofMinutes(5) : downloadUrlDuration;
    }
}
