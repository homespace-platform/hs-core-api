package com.hs.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DisputePaymentRequest(
        @NotBlank(message = "Lý do khiếu nại không được để trống")
        @Size(max=500) String reason
) {}
