package com.hs.api.controller.payment;

import com.hs.api.facade.OnboardingFacade;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.payment.dto.BankAccountRequest;
import com.hs.payment.dto.BankAccountResponse;
import com.hs.payment.dto.UpdateBankAccountDefaultsRequest;
import com.hs.payment.service.BankAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/bank-accounts")
@RequiredArgsConstructor
public class BankAccountController {

    private final BankAccountService bankAccountService;
    private final OnboardingFacade onboardingFacade;

    private String requireUserId() {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null || ctx.userId() == null || ctx.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return ctx.userId();
    }

    @GetMapping
    public ApiResponse<List<BankAccountResponse>> getMyBankAccounts() {
        String userId = requireUserId();
        return ApiResponse.<List<BankAccountResponse>>builder()
                .result(bankAccountService.getUserBankAccounts(userId))
                .build();
    }

    @GetMapping("/{id}")
    public ApiResponse<BankAccountResponse> getBankAccount(@PathVariable String id) {
        String userId = requireUserId();
        return ApiResponse.<BankAccountResponse>builder()
                .result(bankAccountService.getBankAccount(id, userId, true))
                .build();
    }

    @PostMapping
    public ApiResponse<BankAccountResponse> createBankAccount(@RequestBody @Valid BankAccountRequest request) {
        String userId = requireUserId();
        BankAccountResponse response = bankAccountService.createBankAccount(userId, request);
        onboardingFacade.completeOnboardingIfEligible(userId);
        return ApiResponse.<BankAccountResponse>builder()
                .message("Tài khoản ngân hàng đã được lưu thành công")
                .result(response)
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<BankAccountResponse> updateBankAccount(
            @PathVariable String id,
            @RequestBody @Valid BankAccountRequest request) {
        String userId = requireUserId();
        BankAccountResponse response = bankAccountService.updateBankAccount(id, userId, request);
        return ApiResponse.<BankAccountResponse>builder()
                .message("Cập nhật tài khoản ngân hàng thành công")
                .result(response)
                .build();
    }

    @PutMapping("/{id}/defaults")
    public ApiResponse<BankAccountResponse> updateDefaults(
            @PathVariable String id,
            @RequestBody @Valid UpdateBankAccountDefaultsRequest request) {
        String userId = requireUserId();
        BankAccountResponse response = bankAccountService.updateDefaults(id, userId, request);
        return ApiResponse.<BankAccountResponse>builder()
                .message("Cập nhật tài khoản mặc định thành công")
                .result(response)
                .build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteBankAccount(@PathVariable String id) {
        String userId = requireUserId();
        bankAccountService.deleteBankAccount(id, userId);
        return ApiResponse.<Void>builder()
                .message("Đã xóa tài khoản ngân hàng thành công")
                .build();
    }
}
