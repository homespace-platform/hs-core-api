package com.hs.user.config.database;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "homespace.bootstrap.admin")
public record BootstrapAdminProperties(
        boolean enabled,
        String username,
        String email,
        String password,
        String firstName,
        String lastName
) {

    public BootstrapAdminProperties {
        username = normalize(username);
        email = normalize(email) == null ? null : normalize(email).toLowerCase(Locale.ROOT);
        firstName = normalize(firstName);
        lastName = normalize(lastName);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
