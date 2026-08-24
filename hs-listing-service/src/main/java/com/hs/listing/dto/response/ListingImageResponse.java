package com.hs.listing.dto.response;

import com.hs.listing.model.ListingImage;

public record ListingImageResponse(
        String id,
        String storageId,
        Integer sortOrder,
        Boolean cover
) {
    public static ListingImageResponse from(ListingImage image) {
        return new ListingImageResponse(image.getId(), image.getStorageId(), image.getSortOrder(), image.getCover());
    }
}
