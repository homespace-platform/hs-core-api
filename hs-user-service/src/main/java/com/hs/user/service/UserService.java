package com.hs.user.service;

import com.hs.user.dto.request.OnboardingRequest;
import com.hs.user.dto.request.UpdateAvatarRequest;
import com.hs.user.dto.request.UpdatePasswordRequest;
import com.hs.user.dto.request.UpdateProfileRequest;
import com.hs.user.dto.request.UserRoleAssign;
import com.hs.user.dto.response.UserPermissionsResponse;
import com.hs.user.dto.response.UserProfileResponse;

import org.springframework.transaction.annotation.Transactional;

public interface UserService {
    void processOnboarding(OnboardingRequest onboardingRequest);

    @Transactional(readOnly = true)
    UserPermissionsResponse getUserPermissions(String userId);

    void updateUserPassword(UpdatePasswordRequest request);

    UserProfileResponse getUserProfile();

    void updateUserProfile(UpdateProfileRequest request);

    void updateUserAvatar(UpdateAvatarRequest request);

    void updateUserStatus(String userId, boolean enabled);

    void verifyCurrentUserEmail();

    void assignRole(UserRoleAssign request);
}

