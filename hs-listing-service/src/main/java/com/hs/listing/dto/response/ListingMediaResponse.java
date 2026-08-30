package com.hs.listing.dto.response;

import com.hs.listing.model.constant.ListingEnums.MediaType;

public record ListingMediaResponse(
        String id,
        String storageObjectId,
        MediaType mediaType,
        int sortOrder,
        boolean cover,
        String url,
        String contentType,
        long sizeBytes
) {
}
