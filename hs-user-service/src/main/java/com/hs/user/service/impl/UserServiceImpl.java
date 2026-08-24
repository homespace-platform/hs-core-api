package com.hs.user.service.impl;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
import com.hs.user.dto.request.SetInitialPasswordRequest;
import com.hs.user.dto.request.AdminCreateUserRequest;
import com.hs.user.dto.request.AdminUpdateUserRequest;
import com.hs.user.dto.response.AdminCreateUserResponse;
import com.hs.user.dto.response.UserPermissionsResponse;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.dto.response.UserResponse;
import com.hs.user.mapper.UserMapper;
import com.hs.user.model.Address;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.repository.AddressRepository;
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
        AddressRepository addressRepository;
        KeycloakUserService keycloakUserService;
        CurrentUserUtils currentUserUtils;

        @Override
        public AdminCreateUserResponse createUser(AdminCreateUserRequest request) {
                return keycloakUserService.createUser(request);
        }

        @Override
        public UserResponse updateUser(String userId, AdminUpdateUserRequest request) {
                User user = userRepository.findById(userId)
                                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));

                validateUniqueAccountFields(user, request.username(), request.email(), request.phone());

                keycloakUserService.updateUserIfChanged(
                                userId,
                                UpdateKeycloakUserRequest.builder()
                                                .username(trimToNull(request.username()))
                                                .email(trimToNull(request.email()))
                                                .firstName(trimToNull(request.firstName()))
                                                .lastName(trimToNull(request.lastName()))
                                                .phoneNumber(trimToNull(request.phone()))
                                                .build());

                if (hasText(request.username())) {
                        user.setUsername(request.username().trim());
                }
                if (hasText(request.email())) {
                        user.setEmail(request.email().trim().toLowerCase());
                }
                if (hasText(request.firstName())) {
                        user.setFirstName(request.firstName().trim());
                }
                if (hasText(request.lastName())) {
                        user.setLastName(request.lastName().trim());
                }
                if (hasText(request.phone())) {
                        user.setPhone(request.phone().trim());
                }
                if (request.dob() != null) {
                        user.setDob(request.dob());
                }
                if (request.gender() != null) {
                        user.setGender(request.gender());
                }

                applyRoleChange(user, request.roleId());

                userRepository.save(user);
                log.info("Admin updated user {}", userId);
                return toUserResponse(user);
        }

        @Override
        public void resendInvitation(String userId) {
                userRepository.findById(userId)
                                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));
                keycloakUserService.resendInvitation(userId);
        }

        @Override
        @Transactional(readOnly = true)
        public Page<@NonNull UserResponse> findAllUsers(Pageable pageable) {
                Page<User> users = userRepository.findAll(pageable);
                Map<String, User> actors = loadActors(users.getContent());
                return users.map(user -> UserMapper.mapToUserResponse(user, actors));
        }

        @Override
        @Transactional(readOnly = true)
        public UserResponse findUserById(String userId) {
                User user = userRepository.findById(userId)
                                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));
                return toUserResponse(user);
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
                if (!Boolean.TRUE.equals(user.getActive())) {
                        throw new AppException(UserErrorCode.USER_DISABLED);
                }

                var role = user.getRole() != null && Boolean.TRUE.equals(user.getRole().getActive())
                                ? user.getRole()
                                : null;
                var permissions = role == null || role.getPermissions() == null
                                ? Collections.<String>emptySet()
                                : role.getPermissions().stream()
                                                .filter(permission -> Boolean.TRUE.equals(permission.getActive()))
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
                User user = currentUserUtils.getCurrentUser();
                keycloakUserService.updatePassword(user.getId(), user.getUsername(), request);
        }

        @Override
        public void setInitialPassword(SetInitialPasswordRequest request) {
                currentUserUtils.getCurrentUser();
                keycloakUserService.setInitialPassword(currentUserUtils.getCurrentUserId(), request);
        }

        @Override
        @Transactional(readOnly = true)
        public boolean hasPassword() {
                currentUserUtils.getCurrentUser();
                return keycloakUserService.hasPassword(currentUserUtils.getCurrentUserId());
        }

        @Override
        @Transactional(readOnly = true)
        public UserProfileResponse getUserProfile() {
                User user = currentUserUtils.getCurrentUser();
                Address address = addressRepository.findByUser_IdAndActiveTrue(user.getId()).orElse(null);
                return UserMapper.mapToUserProfileResponse(user, address);
        }

        @Override
        public void updateUserProfile(UpdateProfileRequest request) {
                User user = currentUserUtils.getCurrentUser();
                validateUniqueAccountFields(user, request.username(), request.email(), request.phone());

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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));

        if (!enabled && userId.equals(currentUserUtils.getCurrentUserId())) {
            throw new AppException(UserErrorCode.USER_CANNOT_DISABLE_SELF);
        }

        user.setActive(enabled);
        userRepository.save(user);

        keycloakUserService.updateUserIfChanged(
                userId,
                UpdateKeycloakUserRequest.builder()
                        .enabled(enabled)
                        .build());

        log.info("Requested user {} status update to enabled={}", userId, enabled);
    }

        private UserResponse toUserResponse(User user) {
                return UserMapper.mapToUserResponse(user, loadActors(List.of(user)));
        }

        private Map<String, User> loadActors(List<User> users) {
                Set<String> actorIds = new HashSet<>();
                for (User user : users) {
                        if (hasText(user.getCreatedBy())) {
                                actorIds.add(user.getCreatedBy());
                        }
                        if (hasText(user.getUpdatedBy())) {
                                actorIds.add(user.getUpdatedBy());
                        }
                }
                if (actorIds.isEmpty()) {
                        return Map.of();
                }
                return userRepository.findAllById(actorIds).stream()
                                .collect(Collectors.toMap(User::getId, Function.identity()));
        }

        private void validateUniqueAccountFields(User user, String username, String email, String phone) {
                if (hasText(username)
                                && !username.equals(user.getUsername())
                                && userRepository.existsByUsernameAndIdNot(username, user.getId())) {
                        throw new AppException(UserErrorCode.USERNAME_EXISTED);
                }

                if (hasText(email)
                                && !email.equals(user.getEmail())
                                && userRepository.existsByEmailAndIdNot(email, user.getId())) {
                        throw new AppException(UserErrorCode.EMAIL_EXISTED);
                }

                if (hasText(phone)
                                && !phone.equals(user.getPhone())
                                && userRepository.existsByPhoneAndIdNot(phone, user.getId())) {
                        throw new AppException(UserErrorCode.PHONE_EXISTED);
                }
        }

        private void applyRoleChange(User user, String roleId) {
                if (!hasText(roleId)) {
                        return;
                }
                if (user.getId().equals(currentUserUtils.getCurrentUserId())) {
                        throw new AppException(UserErrorCode.USER_CANNOT_UPDATE_OWN_ROLE);
                }

                Role role = roleRepository
                                .findByIdAndActiveTrue(roleId.trim())
                                .orElseThrow(() -> new AppException(UserErrorCode.ROLE_NOT_EXISTED));

                if (user.getRole() != null && role.getId().equals(user.getRole().getId())) {
                        return;
                }

                user.setRole(role);
        }

        private boolean hasText(String value) {
                return value != null && !value.isBlank();
        }

        private String trimToNull(String value) {
                return hasText(value) ? value.trim() : null;
        }

}

