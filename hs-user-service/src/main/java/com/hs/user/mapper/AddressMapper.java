package com.hs.user.mapper;
import com.hs.user.dto.response.AddressResponse;
import com.hs.user.model.Address;
public final class AddressMapper {
    private AddressMapper() {}
    public static AddressResponse mapToAddressResponse(Address a) {
        if(a==null) return null;
        return AddressResponse.builder().id(a.getId()).refId(a.getRefId()).display(a.getDisplay()).name(a.getName())
            .houseNumber(a.getHouseNumber()).street(a.getStreet()).address(a.getAddress()).cityId(a.getCityId()).city(a.getCity())
            .districtId(a.getDistrictId()).district(a.getDistrict()).wardId(a.getWardId()).ward(a.getWard())
            .latitude(a.getLatitude()).longitude(a.getLongitude()).addressNote(a.getAddressNote()).active(a.getActive())
            .createdAt(a.getCreatedAt()).updatedAt(a.getUpdatedAt()).build();
    }
}
