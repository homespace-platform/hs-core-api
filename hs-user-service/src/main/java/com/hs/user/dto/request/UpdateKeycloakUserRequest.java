package com.hs.user.dto.request;

import lombok.Builder;

@Builder
public record UpdateKeycloakUserRequest(
        String username,
        String email,
        String firstName,
        String lastName,
        String phoneNumber,
        String avatarUrl,
        Boolean enabled
) {
    public boolean hasAnyValue() {
        return hasText(username)
                || hasText(email)
                || hasText(firstName)
                || hasText(lastName)
                || hasText(phoneNumber)
                || hasText(avatarUrl)
                || enabled != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

