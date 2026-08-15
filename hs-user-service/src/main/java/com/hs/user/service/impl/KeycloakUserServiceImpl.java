package com.hs.user.service.impl;

import java.util.Objects;

import jakarta.ws.rs.WebApplicationException;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import com.hs.user.advice.base.AppException;
import com.hs.user.config.security.KeycloakPasswordGrantClientFactory;
import com.hs.user.constant.base.ErrorCode;
import com.hs.user.dto.request.UpdateKeycloakUserRequest;
import com.hs.user.dto.request.UpdatePasswordRequest;
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

    RealmResource keycloakRealm;
    KeycloakPasswordGrantClientFactory keycloakPasswordGrantClientFactory;

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
            Boolean resolvedEmailVerified = request.emailVerified() != null
                    ? request.emailVerified()
                    : userRepresentation.isEmailVerified();
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

            userResource.update(userRepresentation);
            log.info("Requested Keycloak user information update for user {}", userId);
        } catch (WebApplicationException exception) {
            log.error("Failed to update Keycloak user {}: {}", userId, exception.getMessage());
            throw new AppException(ErrorCode.KEYCLOAK_USER_UPDATE_FAILED);
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
            throw new AppException(ErrorCode.KEYCLOAK_PASSWORD_UPDATE_FAILED);
        }
    }

    private void verifyOldPassword(String username, String oldPassword) {
        try (Keycloak keycloak = keycloakPasswordGrantClientFactory.createPasswordGrantClient(username, oldPassword)) {
            keycloak.tokenManager().getAccessToken();
        } catch (WebApplicationException exception) {
            throw new AppException(ErrorCode.INVALID_OLD_PASSWORD);
        }
    }

    private boolean isUserChanged(
            UserRepresentation userRepresentation,
            String username,
            String email,
            String firstName,
            String lastName,
            Boolean enabled,
            Boolean emailVerified
    ) {
        return !Objects.equals(userRepresentation.getUsername(), username)
                || !Objects.equals(userRepresentation.getEmail(), email)
                || !Objects.equals(userRepresentation.getFirstName(), firstName)
                || !Objects.equals(userRepresentation.getLastName(), lastName)
                || !Objects.equals(userRepresentation.isEnabled(), enabled)
                || !Objects.equals(userRepresentation.isEmailVerified(), emailVerified);
    }

    private String resolveValue(String requestValue, String currentValue) {
        return hasText(requestValue) ? requestValue : currentValue;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

