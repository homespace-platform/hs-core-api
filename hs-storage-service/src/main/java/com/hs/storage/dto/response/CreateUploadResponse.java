package com.hs.storage.dto.response;

import java.time.Instant;

public record CreateUploadResponse(
        String storageId,
        String uploadUrl,
        String method,
        String objectKey,
        Instant expiresAt
) {
}
