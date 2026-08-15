package com.hs.user.dto.request;

import lombok.Builder;

@Builder
public record UpdateKeycloakUserRequest(
        String username,
        String email,
        String firstName,
        String lastName,
        Boolean enabled,
        Boolean emailVerified
) {
    public boolean hasAnyValue() {
        return hasText(username)
                || hasText(email)
                || hasText(firstName)
                || hasText(lastName)
                || enabled != null
                || emailVerified != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

