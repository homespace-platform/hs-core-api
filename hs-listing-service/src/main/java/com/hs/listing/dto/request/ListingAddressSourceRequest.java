package com.hs.listing.dto.request; import com.hs.listing.model.constant.ListingEnums.AddressSourceType; import jakarta.validation.Valid; import jakarta.validation.constraints.NotNull;
public record ListingAddressSourceRequest(@NotNull AddressSourceType type,String savedAddressId,@Valid ListingAddressRequest address) {}
