package com.hs.user.service.impl;

import java.util.Collections;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.request.OnboardingRequest;
import com.hs.user.dto.request.UpdateKeycloakUserRequest;
import com.hs.user.dto.request.UpdatePasswordRequest;
import com.hs.user.dto.request.UpdateProfileRequest;
import com.hs.user.dto.request.UserRoleAssign;
import com.hs.user.dto.request.SetInitialPasswordRequest;
import com.hs.user.dto.response.UserPermissionsResponse;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.dto.response.UserResponse;
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
        @Transactional(readOnly = true)
        public Page<@NonNull UserResponse> findAllUsers(Pageable pageable) {
                return userRepository.findAll(pageable)
                                .map(UserMapper::mapToUserResponse);
        }

        @Override
        @Transactional(readOnly = true)
        public UserResponse findUserById(String userId) {
                return userRepository.findById(userId)
                                .map(UserMapper::mapToUserResponse)
                                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));
        }

        @Override
        public void processOnboarding(OnboardingRequest onboardingRequest) {
                User user = currentUserUtils.getCurrentUser();

                if (Boolean.TRUE.equals(user.getOnBoarded())) {
                        throw new AppException(UserErrorCode.USER_ALREADY_ONBOARDED);
                }

                if (hasText(onboardingRequest.phone())
                                && userRepository.existsByPhoneAndIdNot(onboardingRequest.phone(), user.getId())) {
                        throw new AppException(UserErrorCode.PHONE_EXISTED);
                }

                keycloakUserService.updateUserIfChanged(
                                user.getId(),
                                UpdateKeycloakUserRequest.builder()
                                                .firstName(trimToNull(onboardingRequest.firstName()))
                                                .lastName(trimToNull(onboardingRequest.lastName()))
                                                .phoneNumber(trimToNull(onboardingRequest.phone()))
                                                .build());

                if (hasText(onboardingRequest.firstName())) {
                        user.setFirstName(onboardingRequest.firstName().trim());
                }
                if (hasText(onboardingRequest.lastName())) {
                        user.setLastName(onboardingRequest.lastName().trim());
                }
                if (hasText(onboardingRequest.phone())) {
                        user.setPhone(onboardingRequest.phone().trim());
                }
                if (onboardingRequest.dob() != null) {
                        user.setDob(onboardingRequest.dob());
                }
                if (onboardingRequest.gender() != null) {
                        user.setGender(onboardingRequest.gender());
                }
                user.setOnBoarded(true);

                userRepository.save(user);
                log.info("Onboarding completed for user {}", user.getId());
        }

        @Override
        public UserPermissionsResponse getUserPermissions(String userId) {
                User user = userRepository.findById(userId)
                                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));

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
                                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));

                keycloakUserService.updatePassword(userId, user.getUsername(), request);
        }

        @Override
        public void setInitialPassword(SetInitialPasswordRequest request) {
                keycloakUserService.setInitialPassword(currentUserUtils.getCurrentUserId(), request);
        }

        @Override
        @Transactional(readOnly = true)
        public boolean hasPassword() {
                return keycloakUserService.hasPassword(currentUserUtils.getCurrentUserId());
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
                                                .phoneNumber(request.phone())
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
        public void updateUserAvatar(String avatarUrl, String avatarStorageId) {
                User user = currentUserUtils.getCurrentUser();

                keycloakUserService.updateUserIfChanged(
                                user.getId(),
                                UpdateKeycloakUserRequest.builder()
                                                .avatarUrl(avatarUrl)
                                                .build());

                user.setAvatarUrl(avatarUrl);
                user.setAvatarStorageId(avatarStorageId);
                userRepository.save(user);
                log.info("Updated avatar for user {}", user.getId());
        }

        @Override
        public void updateUserStatus(String userId, boolean enabled) {
                userRepository.findById(userId)
                                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));

                keycloakUserService.updateUserIfChanged(
                                userId,
                                UpdateKeycloakUserRequest.builder()
                                                .enabled(enabled)
                                                .build());

                log.info("Requested user {} status update to enabled={}", userId, enabled);
        }

        @Override
        public void assignRole(UserRoleAssign request) {
                User user = userRepository.findById(request.userId())
                                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));
                Role role = roleRepository
                                .findById(request.roleId())
                                .orElseThrow(() -> new AppException(UserErrorCode.ROLE_NOT_EXISTED));

                if (user.getRole() != null && request.roleId().equals(user.getRole().getId())) {
                        throw new AppException(UserErrorCode.USER_ALREADY_HAS_ROLE);
                }

                user.setRole(role);
                userRepository.save(user);
                log.info("Assigned role {} to user {}", role.getId(), user.getId());
        }

        private void validateUniqueAccountFields(User user, UpdateProfileRequest request) {
                if (hasText(request.username())
                                && !request.username().equals(user.getUsername())
                                && userRepository.existsByUsernameAndIdNot(request.username(), user.getId())) {
                        throw new AppException(UserErrorCode.USERNAME_EXISTED);
                }

                if (hasText(request.email())
                                && !request.email().equals(user.getEmail())
                                && userRepository.existsByEmailAndIdNot(request.email(), user.getId())) {
                        throw new AppException(UserErrorCode.EMAIL_EXISTED);
                }

                if (hasText(request.phone())
                                && !request.phone().equals(user.getPhone())
                                && userRepository.existsByPhoneAndIdNot(request.phone(), user.getId())) {
                        throw new AppException(UserErrorCode.PHONE_EXISTED);
                }
        }

        private boolean hasText(String value) {
                return value != null && !value.isBlank();
        }

        private String trimToNull(String value) {
                return hasText(value) ? value.trim() : null;
        }

}

