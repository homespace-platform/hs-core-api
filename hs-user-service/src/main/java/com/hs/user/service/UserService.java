package com.hs.user.service;

import com.hs.user.dto.request.AdminCreateUserRequest;
import com.hs.user.dto.request.AdminUpdateUserRequest;
import com.hs.user.dto.request.OnboardingRequest;
import com.hs.user.dto.request.UpdatePasswordRequest;
import com.hs.user.dto.request.UpdateProfileRequest;
import com.hs.user.dto.request.SetInitialPasswordRequest;
import com.hs.user.dto.response.UserPermissionsResponse;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.dto.response.UserResponse;
import com.hs.user.dto.response.AdminCreateUserResponse;

import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {
    AdminCreateUserResponse createUser(AdminCreateUserRequest request);

    UserResponse updateUser(String userId, AdminUpdateUserRequest request);

    void resendInvitation(String userId);

    @Transactional(readOnly = true)
    Page<@NonNull UserResponse> findAllUsers(Pageable pageable);

    @Transactional(readOnly = true)
    UserResponse findUserById(String userId);

    void processOnboarding(OnboardingRequest onboardingRequest);

    @Transactional(readOnly = true)
    UserPermissionsResponse getUserPermissions(String userId);

    void updateUserPassword(UpdatePasswordRequest request);

    void setInitialPassword(SetInitialPasswordRequest request);

    boolean hasPassword();

    UserProfileResponse getUserProfile();

    void updateUserProfile(UpdateProfileRequest request);

    void updateUserAvatar(String avatarUrl, String avatarStorageId);

    void updateUserStatus(String userId, boolean enabled);
}
