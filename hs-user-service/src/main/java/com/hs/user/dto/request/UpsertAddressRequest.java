package com.hs.user.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record UpsertAddressRequest(@NotBlank @Size(max=4096) String refId,@Size(max=255) String addressNote) {}
