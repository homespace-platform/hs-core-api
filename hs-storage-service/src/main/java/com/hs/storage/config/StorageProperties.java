package com.hs.storage.config;

import java.time.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "storage.s3")
@Validated
public record StorageProperties(
        @NotBlank String bucket,
        @NotBlank String region,
        @NotNull Duration uploadUrlDuration,
        @NotNull Duration downloadUrlDuration
) {
}
