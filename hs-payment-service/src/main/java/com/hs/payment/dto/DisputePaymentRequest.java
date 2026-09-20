package com.hs.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record DisputePaymentRequest(
        @NotBlank(message = "Lý do khiếu nại không được để trống")
        String reason
) {}
