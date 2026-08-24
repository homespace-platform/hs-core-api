package com.hs.user.dto.response;

import java.time.Instant;

import lombok.Builder;

@Builder
public record AddressResponse(
        String id,
        String provinceCode,
        String provinceName,
        String wardCode,
        String wardName,
        String streetLine,
        String fullAddress,
        Boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
