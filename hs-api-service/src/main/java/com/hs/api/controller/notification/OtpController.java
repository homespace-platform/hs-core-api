package com.hs.api.controller.notification;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hs.common.dto.ApiResponse;
import com.hs.notification.dto.request.CreateOtpChallengeRequest;
import com.hs.notification.dto.request.VerifyOtpRequest;
import com.hs.notification.dto.response.OtpChallengeResponse;
import com.hs.notification.dto.response.OtpVerificationResponse;
import com.hs.notification.service.OtpService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/notifications/otp/challenges")
@RequiredArgsConstructor
public class OtpController {
    private final OtpService otpService;

    @PostMapping
    public ApiResponse<OtpChallengeResponse> createChallenge(
            @RequestBody @Valid CreateOtpChallengeRequest request) {
        return ApiResponse.<OtpChallengeResponse>builder()
                .message("OTP challenge created")
                .result(otpService.createChallenge(request))
                .build();
    }

    @PostMapping("/{challengeId}/resend")
    public ApiResponse<OtpChallengeResponse> resend(@PathVariable String challengeId) {
        return ApiResponse.<OtpChallengeResponse>builder()
                .message("OTP challenge resent")
                .result(otpService.resend(challengeId))
                .build();
    }

    @PostMapping("/{challengeId}/verify")
    public ApiResponse<OtpVerificationResponse> verify(
            @PathVariable String challengeId,
            @RequestBody @Valid VerifyOtpRequest request) {
        return ApiResponse.<OtpVerificationResponse>builder()
                .message("OTP verified")
                .result(otpService.verify(challengeId, request.code()))
                .build();
    }
}
