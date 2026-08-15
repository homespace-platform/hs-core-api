package com.hs.user.dto.response;

import lombok.Builder;

import java.util.Set;

@Builder
public record UserPermissionsResponse(
        String email,
        String role,
        Set<String> permissions
) {}

