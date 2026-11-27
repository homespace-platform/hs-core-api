package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.user.dto.request.AdminKycRequest;
import com.hs.user.dto.response.AdminKycStatusResponse;
import com.hs.user.service.kyc.AdminKycService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/admin/users/{userId}/kyc")
public class AdminKycController {
    private final AdminKycService kyc;

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN') and hasAuthority('KYC_VIEW')")
    public ApiResponse<AdminKycStatusResponse> status(@PathVariable String userId) {
        return ApiResponse.<AdminKycStatusResponse>builder().result(kyc.status(userId)).build();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ADMIN') and hasAuthority('KYC_MANAGE')")
    public ApiResponse<AdminKycStatusResponse> set(@PathVariable String userId, @RequestBody AdminKycRequest request) {
        return ApiResponse.<AdminKycStatusResponse>builder().result(kyc.set(userId, request)).build();
    }
}
