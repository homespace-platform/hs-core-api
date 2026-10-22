package com.hs.listing.dto.response;
import com.hs.listing.model.ListingAddress;
import java.math.BigDecimal;
public record ListingAddressResponse(String id,String listingId,String refId,String display,String name,String houseNumber,
        String street,String address,String cityId,String city,String districtId,String district,String wardId,String ward,
        BigDecimal latitude,BigDecimal longitude,String addressNote) {
    public static ListingAddressResponse from(ListingAddress a) {
        return a==null?null:new ListingAddressResponse(a.getId(),a.getListingId(),a.getRefId(),a.getDisplay(),a.getName(),a.getHouseNumber(),
            a.getStreet(),a.getAddress(),a.getCityId(),a.getCity(),a.getDistrictId(),a.getDistrict(),a.getWardId(),a.getWard(),
            a.getLatitude(),a.getLongitude(),a.getAddressNote());
    }
}
