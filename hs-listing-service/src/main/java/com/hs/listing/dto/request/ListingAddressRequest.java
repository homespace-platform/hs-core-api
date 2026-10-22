package com.hs.listing.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ListingAddressRequest(@NotBlank @Size(max=4096) String refId,@Size(max=255) String addressNote) {}
