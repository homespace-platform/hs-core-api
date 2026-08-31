package com.hs.listing.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminCreateListingRequest(
        @NotBlank String ownerId,
        @NotNull @Valid CreateListingRequest listing
) {
}
