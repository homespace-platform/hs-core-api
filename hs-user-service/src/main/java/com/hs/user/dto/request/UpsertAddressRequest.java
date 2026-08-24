package com.hs.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertAddressRequest(
        @NotBlank(message = "Province code is required")
        @Size(max = 20)
        String provinceCode,

        @NotBlank(message = "Province name is required")
        @Size(max = 100)
        String provinceName,

        @NotBlank(message = "Ward code is required")
        @Size(max = 20)
        String wardCode,

        @NotBlank(message = "Ward name is required")
        @Size(max = 100)
        String wardName,

        @NotBlank(message = "Street line is required")
        @Size(max = 255)
        String streetLine
) {
}
