package com.hs.listing.dto.request;
import jakarta.validation.constraints.*;
public record ListingPolicyRequest(@NotBlank @Size(max=64) String code,
        @NotBlank @Size(max=2000) String value, @Size(max=1000) String note) {}
