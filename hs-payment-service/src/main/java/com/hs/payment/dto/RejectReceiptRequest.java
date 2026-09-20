package com.hs.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectReceiptRequest(
        @NotBlank(message = "Lý do từ chối không được để trống")
        String reason
) {}
