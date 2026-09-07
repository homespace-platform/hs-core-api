package com.hs.listing.dto.request; import com.hs.listing.model.constant.HandoverCondition; import jakarta.validation.constraints.*;
public record ListingFurnishingRequest(@Size(max=64) String itemCode,@Size(max=255) String assetName,@NotNull @Min(1) @Max(999) Integer quantity,@NotNull HandoverCondition handoverCondition,@Size(max=255) String conditionNote) {}
