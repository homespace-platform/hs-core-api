package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.user.dto.request.AdminVerifyKycRequest;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.service.kyc.AdminKycService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/admin/users/{userId}/kyc")
@PreAuthorize("hasAuthority('ADMIN')")
public class AdminKycController {
    private final AdminKycService kyc;

    @GetMapping
    public ApiResponse<KycStatusResponse> status(@PathVariable String userId) {
        return ApiResponse.<KycStatusResponse>builder().result(kyc.status(userId)).build();
    }

    @PostMapping("/verify")
    public ApiResponse<KycStatusResponse> verify(@PathVariable String userId, @RequestBody @Valid AdminVerifyKycRequest request) {
        return ApiResponse.<KycStatusResponse>builder().result(kyc.verify(userId, request.cccd()))
                .message("Đã xác minh danh tính người dùng.").build();
    }
}
