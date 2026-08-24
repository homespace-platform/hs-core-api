package com.hs.user.service;

import com.hs.user.dto.request.UpsertAddressRequest;
import com.hs.user.dto.response.AddressResponse;

public interface AddressService {

    AddressResponse getCurrentUserAddress();

    AddressResponse upsertCurrentUserAddress(UpsertAddressRequest request);

    void deleteCurrentUserAddress();
}
