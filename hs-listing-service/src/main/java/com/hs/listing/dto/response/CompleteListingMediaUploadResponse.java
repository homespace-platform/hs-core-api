package com.hs.listing.dto.response;

public record CompleteListingMediaUploadResponse(
        String objectKey,
        String publicUrl,
        String contentType,
        Long sizeBytes
) {
}
