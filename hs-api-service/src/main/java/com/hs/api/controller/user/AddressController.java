package com.hs.api.controller.user;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hs.common.dto.ApiResponse;
import com.hs.user.dto.request.UpsertAddressRequest;
import com.hs.user.dto.response.AddressResponse;
import com.hs.user.service.AddressService;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@RequestMapping("/users/me/address")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AddressController {

    AddressService addressService;

    @GetMapping
    public ApiResponse<AddressResponse> getCurrentUserAddress() {
        return ApiResponse.<AddressResponse>builder()
                .result(addressService.getCurrentUserAddress())
                .build();
    }

    @PutMapping
    public ApiResponse<AddressResponse> upsertCurrentUserAddress(
            @RequestBody @Valid UpsertAddressRequest request) {
        return ApiResponse.<AddressResponse>builder()
                .message("Address saved successfully")
                .result(addressService.upsertCurrentUserAddress(request))
                .build();
    }

    @DeleteMapping
    public ApiResponse<Void> deleteCurrentUserAddress() {
        addressService.deleteCurrentUserAddress();
        return ApiResponse.<Void>builder()
                .message("Address deleted successfully")
                .build();
    }
}
