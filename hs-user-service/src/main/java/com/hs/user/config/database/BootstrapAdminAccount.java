package com.hs.user.config.database;

import java.util.Locale;

/**
 * One bootstrap admin account (Keycloak + DB seed).
 */
public record BootstrapAdminAccount(
        boolean enabled,
        String username,
        String email,
        String password,
        String phoneNumber,
        String firstName,
        String lastName,
        String cccd
) {

    public BootstrapAdminAccount {
        username = normalize(username);
        email = normalize(email) == null ? null : normalize(email).toLowerCase(Locale.ROOT);
        phoneNumber = normalize(phoneNumber);
        firstName = normalize(firstName);
        lastName = normalize(lastName);
        cccd = normalize(cccd);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
