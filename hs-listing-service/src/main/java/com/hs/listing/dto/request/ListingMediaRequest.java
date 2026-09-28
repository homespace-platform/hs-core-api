package com.hs.listing.dto.request;

import com.hs.listing.model.constant.ListingEnums.MediaType;
import jakarta.validation.constraints.*;

public record ListingMediaRequest(
        String storageObjectId,
        String sourceMediaId,
        @NotNull MediaType mediaType,
        @NotNull @Min(0) Integer sortOrder,
        boolean cover) {

    public ListingMediaRequest(String storageObjectId, MediaType mediaType, Integer sortOrder, boolean cover) {
        this(storageObjectId, null, mediaType, sortOrder, cover);
    }
}
