package com.hs.listing.dto.response;

import java.time.Instant;

public record CreateListingMediaUploadResponse(
        String uploadUrl,
        String method,
        String objectKey,
        String publicUrl,
        Instant expiresAt
) {
}
