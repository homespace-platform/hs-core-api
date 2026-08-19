package com.hs.storage.dto.response;

import java.time.Instant;

public record StorageUrlResponse(String url, Instant expiresAt) {
}
