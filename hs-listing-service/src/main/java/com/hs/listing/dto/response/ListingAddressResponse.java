package com.hs.listing.dto.response;

import com.hs.user.model.Address;

public record ListingAddressResponse(
        String id,
        String listingId,
        String provinceCode,
        String provinceName,
        String wardCode,
        String wardName,
        String streetLine,
        String fullAddress
) {

    public static ListingAddressResponse from(Address address) {
        if (address == null) {
            return null;
        }
        return new ListingAddressResponse(
                address.getId(),
                address.getListingId(),
                address.getProvinceCode(),
                address.getProvinceName(),
                address.getWardCode(),
                address.getWardName(),
                address.getStreetLine(),
                address.getFullAddress());
    }
}
