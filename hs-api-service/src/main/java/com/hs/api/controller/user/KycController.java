package com.hs.api.controller.user;

import com.hs.common.dto.ApiResponse;
import com.hs.user.dto.response.KycSessionResponse;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.service.KycService;
import com.hs.user.utils.CurrentUserUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/kyc")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class KycController {

    KycService kycService;
    CurrentUserUtils currentUserUtils;

    @GetMapping
    public ApiResponse<KycStatusResponse> getMyKyc() {
        String userId = currentUserUtils.getCurrentUserId();
        return ApiResponse.<KycStatusResponse>builder()
                .result(kycService.getCurrentStatus(userId))
                .build();
    }

    @PostMapping("/session")
    public ApiResponse<KycSessionResponse> createSession() {
        String userId = currentUserUtils.getCurrentUserId();
        return ApiResponse.<KycSessionResponse>builder()
                .message("KYC session created")
                .result(kycService.createOrReuseSession(userId))
                .build();
    }

    @PostMapping("/session/cancel")
    public ApiResponse<KycStatusResponse> cancelSession() {
        String userId = currentUserUtils.getCurrentUserId();
        return ApiResponse.<KycStatusResponse>builder()
                .message("KYC session cancelled")
                .result(kycService.cancelPendingSession(userId))
                .build();
    }
}
