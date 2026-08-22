package com.hs.user.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import com.hs.common.advice.entity.AppException;
import com.hs.user.config.security.KeycloakPasswordGrantClientFactory;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.request.UpdateKeycloakUserRequest;
import com.hs.user.dto.request.UpdatePasswordRequest;
import com.hs.user.dto.request.SetInitialPasswordRequest;
import com.hs.user.dto.request.AdminCreateUserRequest;
import com.hs.user.dto.response.AdminCreateUserResponse;
import com.hs.user.service.KeycloakUserService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class KeycloakUserServiceImpl implements KeycloakUserService {

    private static final String PHONE_NUMBER_ATTRIBUTE = "phoneNumber";
    private static final String PICTURE_ATTRIBUTE = "picture";
    private static final List<String> INITIAL_REQUIRED_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD");

    RealmResource keycloakRealm;
    KeycloakPasswordGrantClientFactory keycloakPasswordGrantClientFactory;

    @Override
    public AdminCreateUserResponse createUser(AdminCreateUserRequest request) {
        UserRepresentation user = new UserRepresentation();
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(trimToNull(request.firstName()));
        user.setLastName(trimToNull(request.lastName()));
        user.setEnabled(request.enabled());
        user.setEmailVerified(false);
        user.setRequiredActions(new ArrayList<>(INITIAL_REQUIRED_ACTIONS));

        String phone = trimToNull(request.phone());
        if (phone != null) {
            user.setAttributes(Map.of(PHONE_NUMBER_ATTRIBUTE, List.of(phone)));
        }

        String userId;
        try (Response response = keycloakRealm.users().create(user)) {
            if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                throw new AppException(UserErrorCode.USER_EXISTED);
            }
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL
                    || response.getLocation() == null) {
                log.error("Keycloak rejected user creation with status {}", response.getStatus());
                throw new AppException(UserErrorCode.KEYCLOAK_USER_CREATE_FAILED);
            }
            userId = extractCreatedUserId(response);
        } catch (AppException exception) {
            throw exception;
        } catch (WebApplicationException exception) {
            log.error("Failed to create Keycloak user {}: {}", username, exception.getMessage());
            throw new AppException(UserErrorCode.KEYCLOAK_USER_CREATE_FAILED);
        }

        boolean invitationSent = false;
        if (Boolean.TRUE.equals(request.sendInvitation())) {
            try {
                sendInvitationEmail(userId, INITIAL_REQUIRED_ACTIONS);
                invitationSent = true;
            } catch (RuntimeException exception) {
                // The account already exists. SMTP failure must not make the create operation look rolled back.
                log.error("Created Keycloak user {}, but failed to send invitation email: {}",
                        userId, exception.getMessage());
            }
        }

        log.info("Created Keycloak user {} and queued synchronization through the Keycloak event publisher", userId);
        return AdminCreateUserResponse.builder()
                .id(userId)
                .username(username)
                .email(email)
                .enabled(Boolean.TRUE.equals(request.enabled()))
                .invitationSent(invitationSent)
                .provisioningStatus("PROVISIONING")
                .build();
    }

    @Override
    public void resendInvitation(String userId) {
        try {
            var userResource = keycloakRealm.users().get(userId);
            UserRepresentation user = userResource.toRepresentation();

            if (!Boolean.TRUE.equals(user.isEnabled())) {
                throw new AppException(UserErrorCode.USER_DISABLED);
            }

            List<String> actions = resolveInvitationActions(user, userId);
            if (actions.isEmpty()) {
                throw new AppException(UserErrorCode.USER_ALREADY_ACTIVATED);
            }

            sendInvitationEmail(userId, actions);
            log.info("Resent invitation email to Keycloak user {}", userId);
        } catch (AppException exception) {
            throw exception;
        } catch (NotFoundException exception) {
            throw new AppException(UserErrorCode.USER_NOT_EXISTED);
        } catch (RuntimeException exception) {
            log.error("Failed to resend invitation for Keycloak user {}: {}", userId, exception.getMessage());
            throw new AppException(UserErrorCode.KEYCLOAK_INVITATION_SEND_FAILED);
        }
    }

    @Override
    public void updateUserIfChanged(String userId, UpdateKeycloakUserRequest request) {
        if (request == null || !request.hasAnyValue()) {
            return;
        }

        try {
            var userResource = keycloakRealm.users().get(userId);
            UserRepresentation userRepresentation = userResource.toRepresentation();

            String resolvedUsername = resolveValue(request.username(), userRepresentation.getUsername());
            String resolvedEmail = resolveValue(request.email(), userRepresentation.getEmail());
            String resolvedFirstName = resolveValue(request.firstName(), userRepresentation.getFirstName());
            String resolvedLastName = resolveValue(request.lastName(), userRepresentation.getLastName());
            Boolean resolvedEnabled = request.enabled() != null ? request.enabled() : userRepresentation.isEnabled();
            Boolean resolvedEmailVerified = userRepresentation.isEmailVerified();
            String resolvedPhoneNumber = resolveValue(
                    request.phoneNumber(),
                    getFirstAttribute(userRepresentation, PHONE_NUMBER_ATTRIBUTE));
            String resolvedAvatarUrl = resolveValue(
                    request.avatarUrl(),
                    getFirstAttribute(userRepresentation, PICTURE_ATTRIBUTE));
            boolean emailChanged = !Objects.equals(userRepresentation.getEmail(), resolvedEmail);
            if (emailChanged) {
                resolvedEmailVerified = false;
            }

            if (!isUserChanged(
                    userRepresentation,
                    resolvedUsername,
                    resolvedEmail,
                    resolvedFirstName,
                    resolvedLastName,
                    resolvedPhoneNumber,
                    resolvedAvatarUrl,
                    resolvedEnabled,
                    resolvedEmailVerified)) {
                log.info("Keycloak user information is unchanged for user {}", userId);
                return;
            }

            userRepresentation.setUsername(resolvedUsername);
            userRepresentation.setEmail(resolvedEmail);
            userRepresentation.setFirstName(resolvedFirstName);
            userRepresentation.setLastName(resolvedLastName);
            userRepresentation.setEnabled(resolvedEnabled);
            userRepresentation.setEmailVerified(resolvedEmailVerified);
            setAttribute(userRepresentation, PHONE_NUMBER_ATTRIBUTE, resolvedPhoneNumber);
            setAttribute(userRepresentation, PICTURE_ATTRIBUTE, resolvedAvatarUrl);

            userResource.update(userRepresentation);
            log.info("Requested Keycloak user information update for user {}", userId);
        } catch (WebApplicationException exception) {
            log.error("Failed to update Keycloak user {}: {}", userId, exception.getMessage());
            throw new AppException(UserErrorCode.KEYCLOAK_USER_UPDATE_FAILED);
        }
    }

    @Override
    public void updatePassword(String userId, String username, UpdatePasswordRequest request) {
        verifyOldPassword(username, request.oldPassword());

        try {
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(request.newPassword());
            credential.setTemporary(false);

            keycloakRealm
                    .users()
                    .get(userId)
                    .resetPassword(credential);

            log.info("Updated Keycloak password for user {}", userId);
        } catch (WebApplicationException exception) {
            log.error("Failed to update Keycloak password for user {}: {}", userId, exception.getMessage());
            throw new AppException(UserErrorCode.KEYCLOAK_PASSWORD_UPDATE_FAILED);
        }
    }

    @Override
    public void setInitialPassword(String userId, SetInitialPasswordRequest request) {
        var userResource = keycloakRealm.users().get(userId);

        if (hasPassword(userId)) {
            throw new AppException(UserErrorCode.PASSWORD_ALREADY_SET);
        }

        try {
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(request.newPassword());
            credential.setTemporary(false);
            userResource.resetPassword(credential);

            log.info("Set initial Keycloak password for user {}", userId);
        } catch (WebApplicationException exception) {
            log.error("Failed to set initial Keycloak password for user {}: {}", userId, exception.getMessage());
            throw new AppException(UserErrorCode.KEYCLOAK_PASSWORD_UPDATE_FAILED);
        }
    }

    @Override
    public boolean hasPassword(String userId) {
        try {
            return keycloakRealm.users()
                    .get(userId)
                    .credentials()
                    .stream()
                    .anyMatch(credential -> CredentialRepresentation.PASSWORD.equals(credential.getType()));
        } catch (WebApplicationException exception) {
            log.error("Failed to read Keycloak credentials for user {}: {}", userId, exception.getMessage());
            throw new AppException(UserErrorCode.KEYCLOAK_CREDENTIAL_READ_FAILED);
        }
    }

    private void sendInvitationEmail(String userId, List<String> actions) {
        keycloakRealm.users().get(userId).executeActionsEmail(actions);
    }

    private List<String> resolveInvitationActions(UserRepresentation user, String userId) {
        List<String> requiredActions = user.getRequiredActions() == null
                ? List.of()
                : user.getRequiredActions();
        List<String> actions = new ArrayList<>();

        boolean emailVerified = Boolean.TRUE.equals(user.isEmailVerified());
        if (!emailVerified || requiredActions.contains("VERIFY_EMAIL")) {
            actions.add("VERIFY_EMAIL");
        }
        if (!hasPassword(userId) || requiredActions.contains("UPDATE_PASSWORD")) {
            actions.add("UPDATE_PASSWORD");
        }
        return actions;
    }

    private void verifyOldPassword(String username, String oldPassword) {
        try (Keycloak keycloak = keycloakPasswordGrantClientFactory.createPasswordGrantClient(username, oldPassword)) {
            keycloak.tokenManager().getAccessToken();
        } catch (WebApplicationException exception) {
            throw new AppException(UserErrorCode.INVALID_OLD_PASSWORD);
        }
    }

    private boolean isUserChanged(
            UserRepresentation userRepresentation,
            String username,
            String email,
            String firstName,
            String lastName,
            String phoneNumber,
            String avatarUrl,
            Boolean enabled,
            Boolean emailVerified
    ) {
        return !Objects.equals(userRepresentation.getUsername(), username)
                || !Objects.equals(userRepresentation.getEmail(), email)
                || !Objects.equals(userRepresentation.getFirstName(), firstName)
                || !Objects.equals(userRepresentation.getLastName(), lastName)
                || !Objects.equals(getFirstAttribute(userRepresentation, PHONE_NUMBER_ATTRIBUTE), phoneNumber)
                || !Objects.equals(getFirstAttribute(userRepresentation, PICTURE_ATTRIBUTE), avatarUrl)
                || !Objects.equals(userRepresentation.isEnabled(), enabled)
                || !Objects.equals(userRepresentation.isEmailVerified(), emailVerified);
    }

    private String getFirstAttribute(UserRepresentation userRepresentation, String attributeName) {
        Map<String, List<String>> attributes = userRepresentation.getAttributes();
        if (attributes == null) {
            return null;
        }

        List<String> values = attributes.get(attributeName);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private void setAttribute(UserRepresentation userRepresentation, String attributeName, String value) {
        Map<String, List<String>> attributes = userRepresentation.getAttributes() == null
                ? new HashMap<>()
                : new HashMap<>(userRepresentation.getAttributes());

        if (hasText(value)) {
            attributes.put(attributeName, new ArrayList<>(List.of(value)));
        }

        userRepresentation.setAttributes(attributes);
    }

    private String resolveValue(String requestValue, String currentValue) {
        return hasText(requestValue) ? requestValue : currentValue;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String extractCreatedUserId(Response response) {
        String path = response.getLocation().getPath();
        int separator = path.lastIndexOf('/');
        if (separator < 0 || separator == path.length() - 1) {
            throw new AppException(UserErrorCode.KEYCLOAK_USER_CREATE_FAILED);
        }
        return path.substring(separator + 1);
    }
}

