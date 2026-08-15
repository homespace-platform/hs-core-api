package com.hs.api.controller.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hs.common.dto.ApiResponse;
import com.hs.user.dto.request.OnboardingRequest;
import com.hs.user.dto.request.UpdatePasswordRequest;
import com.hs.user.dto.request.UpdateProfileRequest;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.service.UserService;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserController {

    UserService userService;

    @PutMapping("/me/onboarding")
    public ApiResponse<Void> onboarding(@RequestBody @Valid OnboardingRequest request) {
        userService.processOnboarding(request);
        return ApiResponse.<Void>builder()
                .message("Onboarding completed")
                .build();
    }

    @PostMapping("/me/password")
    public ApiResponse<Void> updateUserPassword(@RequestBody @Valid UpdatePasswordRequest request) {
        userService.updateUserPassword(request);
        return ApiResponse.<Void>builder()
                .message("Password updated successfully")
                .build();
    }

    @GetMapping("/me/profile")
    public ApiResponse<UserProfileResponse> getUserProfile() {
        return ApiResponse.<UserProfileResponse>builder()
                .result(userService.getUserProfile())
                .build();
    }

    @PostMapping("/me/profile")
    public ApiResponse<Void> updateUserProfile(@RequestBody @Valid UpdateProfileRequest request) {
        userService.updateUserProfile(request);
        return ApiResponse.<Void>builder()
                .message("Profile updated successfully")
                .build();
    }

    @PostMapping("/me/email/verify")
    public ApiResponse<Void> verifyEmail() {
        userService.verifyCurrentUserEmail();
        return ApiResponse.<Void>builder()
                .message("Email verified successfully")
                .build();
    }
}


