package com.hs.listing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompleteListingMediaUploadRequest(
        @NotBlank @Size(max = 500) String objectKey
) {
}
