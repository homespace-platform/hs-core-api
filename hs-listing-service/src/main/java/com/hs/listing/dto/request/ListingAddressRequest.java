package com.hs.listing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ListingAddressRequest(
        @NotBlank @Size(max = 20) String provinceCode,
        @NotBlank @Size(max = 100) String provinceName,
        @NotBlank @Size(max = 20) String wardCode,
        @NotBlank @Size(max = 100) String wardName,
        @NotBlank @Size(max = 255) String streetLine
) {
}
