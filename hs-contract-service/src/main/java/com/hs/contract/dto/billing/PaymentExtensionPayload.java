package com.hs.contract.dto.billing;

import jakarta.validation.constraints.*;

public record PaymentExtensionPayload(@Min(1) @Max(30) int days,
                                      @NotBlank @Size(min = 10, max = 1000) String note) {}
