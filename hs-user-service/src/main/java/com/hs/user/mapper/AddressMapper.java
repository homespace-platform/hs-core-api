package com.hs.user.mapper;

import com.hs.user.dto.response.AddressResponse;
import com.hs.user.model.Address;

public class AddressMapper {

    private AddressMapper() {
    }

    public static AddressResponse mapToAddressResponse(Address address) {
        if (address == null) {
            return null;
        }
        return AddressResponse.builder()
                .id(address.getId())
                .provinceCode(address.getProvinceCode())
                .provinceName(address.getProvinceName())
                .wardCode(address.getWardCode())
                .wardName(address.getWardName())
                .streetLine(address.getStreetLine())
                .fullAddress(address.getFullAddress())
                .active(address.getActive())
                .createdAt(address.getCreatedAt())
                .updatedAt(address.getUpdatedAt())
                .build();
    }
}
