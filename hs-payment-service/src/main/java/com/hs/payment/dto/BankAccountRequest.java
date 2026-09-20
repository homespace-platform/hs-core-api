package com.hs.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record BankAccountRequest(
        @NotBlank(message = "Mã định danh ngân hàng (BIN) không được để trống")
        String bankBin,

        @NotBlank(message = "Mã ngân hàng không được để trống")
        String bankCode,

        @NotBlank(message = "Tên ngân hàng không được để trống")
        String bankName,

        @NotBlank(message = "Số tài khoản không được để trống")
        String accountNumber,

        @NotBlank(message = "Tên chủ tài khoản không được để trống")
        String accountHolderName,

        Boolean defaultForIncomingPayments,
        Boolean defaultForRefunds
) {}
