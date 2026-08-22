package com.hs.user.dto.response;

import lombok.Builder;

@Builder
public record AdminCreateUserResponse(
        String id,
        String username,
        String email,
        boolean enabled,
        boolean invitationSent,
        String provisioningStatus
) {
}
