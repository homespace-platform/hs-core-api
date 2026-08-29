package com.hs.listing.dto.request;

import com.hs.listing.model.constant.ListingMediaType;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateListingMediaUploadRequest(
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 120) String contentType,
        @NotNull @Min(1) Long size,
        @NotNull ListingMediaType mediaType
) {
}
