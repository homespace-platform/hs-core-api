package com.hs.listing.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AddListingImageRequest(
        @NotBlank String storageId,
        @Min(0) Integer sortOrder,
        Boolean cover
) {
}
