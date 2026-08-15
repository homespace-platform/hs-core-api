package com.hs.user.service.impl;

import java.util.Collections;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hs.user.advice.base.AppException;
import com.hs.user.constant.base.ErrorCode;
import com.hs.user.dto.request.OnboardingRequest;
import com.hs.user.dto.request.UpdateAvatarRequest;
import com.hs.user.dto.request.UpdateKeycloakUserRequest;
import com.hs.user.dto.request.UpdatePasswordRequest;
import com.hs.user.dto.request.UpdateProfileRequest;
import com.hs.user.dto.request.UserRoleAssign;
import com.hs.user.dto.response.UserPermissionsResponse;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.mapper.UserMapper;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.KeycloakUserService;
import com.hs.user.service.UserService;
import com.hs.user.utils.CurrentUserUtils;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@Transactional
public class UserServiceImpl implements UserService {

        UserRepository userRepository;
        RoleRepository roleRepository;
        KeycloakUserService keycloakUserService;
        CurrentUserUtils currentUserUtils;

        @Override
        public void processOnboarding(OnboardingRequest onboardingRequest) {
                User user = currentUserUtils.getCurrentUser();

                if (Boolean.TRUE.equals(user.getOnBoarded())) {
                        throw new AppException(ErrorCode.USER_ALREADY_ONBOARDED);
                }

                keycloakUserService.updateUserIfChanged(
                                user.getId(),
                                UpdateKeycloakUserRequest.builder()
                                                .firstName(onboardingRequest.firstName())
                                                .lastName(onboardingRequest.lastName())
                                                .build());

                user.setPhone(onboardingRequest.phone());
                user.setDob(onboardingRequest.dob());
                user.setGender(onboardingRequest.gender());
                user.setOnBoarded(true);

                userRepository.save(user);
                log.info("Onboarding completed for user {}", user.getId());
        }

        @Override
        public UserPermissionsResponse getUserPermissions(String userId) {
                User user = userRepository.findById(userId)
                                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

                var role = user.getRole();
                var permissions = role == null || role.getPermissions() == null
                                ? Collections.<String>emptySet()
                                : role.getPermissions().stream()
                                                .map(permission -> permission.getName())
                                                .collect(Collectors.toSet());

                return UserPermissionsResponse.builder()
                                .email(user.getEmail())
                                .role(role != null ? role.getName() : null)
                                .permissions(permissions)
                                .build();
        }

        @Override
        public void updateUserPassword(UpdatePasswordRequest request) {
                String userId = currentUserUtils.getCurrentUserId();
                User user = userRepository.findById(userId)
                                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

                keycloakUserService.updatePassword(userId, user.getUsername(), request);
        }

        @Override
        @Transactional(readOnly = true)
        public UserProfileResponse getUserProfile() {
                return UserMapper.mapToUserProfileResponse(currentUserUtils.getCurrentUser());
        }

        @Override
        public void updateUserProfile(UpdateProfileRequest request) {
                User user = currentUserUtils.getCurrentUser();
                validateUniqueAccountFields(user, request);

                keycloakUserService.updateUserIfChanged(
                                user.getId(),
                                UpdateKeycloakUserRequest.builder()
                                                .username(request.username())
                                                .email(request.email())
                                                .firstName(request.firstName())
                                                .lastName(request.lastName())
                                                .build());

                if (request.phone() != null) {
                        user.setPhone(request.phone());
                }
                if (request.dob() != null) {
                        user.setDob(request.dob());
                }
                if (request.gender() != null) {
                        user.setGender(request.gender());
                }

                userRepository.save(user);
                log.info("Updated profile for user {}", user.getId());
        }

        @Override
        public void updateUserAvatar(UpdateAvatarRequest request) {
                // TODO Auto-generated method stub
        }

        @Override
        public void updateUserStatus(String userId, boolean enabled) {
                userRepository.findById(userId)
                                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

                keycloakUserService.updateUserIfChanged(
                                userId,
                                UpdateKeycloakUserRequest.builder()
                                                .enabled(enabled)
                                                .build());

                log.info("Requested user {} status update to enabled={}", userId, enabled);
        }

        @Override
        public void verifyCurrentUserEmail() {
                User user = currentUserUtils.getCurrentUser();

                if (Boolean.TRUE.equals(user.getEmailVerified())) {
                        throw new AppException(ErrorCode.EMAIL_ALREADY_VERIFIED);
                }

                keycloakUserService.updateUserIfChanged(
                                user.getId(),
                                UpdateKeycloakUserRequest.builder()
                                                .emailVerified(true)
                                                .build());

                log.info("Requested email verification for user {}", user.getId());
        }

        @Override
        public void assignRole(UserRoleAssign request) {
                User user = userRepository.findById(request.userId())
                                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
                Role role = roleRepository
                                .findById(request.roleId())
                                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_EXISTED));

                if (user.getRole() != null && request.roleId().equals(user.getRole().getId())) {
                        throw new AppException(ErrorCode.USER_ALREADY_HAS_ROLE);
                }

                user.setRole(role);
                userRepository.save(user);
                log.info("Assigned role {} to user {}", role.getId(), user.getId());
        }

        private void validateUniqueAccountFields(User user, UpdateProfileRequest request) {
                if (hasText(request.username())
                                && !request.username().equals(user.getUsername())
                                && userRepository.existsByUsernameAndIdNot(request.username(), user.getId())) {
                        throw new AppException(ErrorCode.USERNAME_EXISTED);
                }

                if (hasText(request.email())
                                && !request.email().equals(user.getEmail())
                                && userRepository.existsByEmailAndIdNot(request.email(), user.getId())) {
                        throw new AppException(ErrorCode.EMAIL_EXISTED);
                }
        }

        private boolean hasText(String value) {
                return value != null && !value.isBlank();
        }

}

