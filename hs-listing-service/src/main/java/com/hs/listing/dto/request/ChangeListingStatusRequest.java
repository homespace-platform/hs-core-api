package com.hs.listing.dto.request;

import com.hs.listing.model.constant.ListingStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeListingStatusRequest(
        @NotNull ListingStatus status,
        @Size(max = 2000) String reason
) {
}
