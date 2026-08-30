package com.hs.listing.dto.request; import com.hs.listing.model.constant.ListingEnums.MediaType; import jakarta.validation.constraints.*;
public record ListingMediaRequest(@NotBlank String storageObjectId,@NotNull MediaType mediaType,@NotNull @Min(0) Integer sortOrder,boolean cover) {}
