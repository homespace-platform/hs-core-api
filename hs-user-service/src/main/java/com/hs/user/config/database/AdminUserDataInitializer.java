package com.hs.user.config.database;

import java.util.ArrayList;
import java.util.List;

import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.user.constant.RoleConstants;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class AdminUserDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RealmResource keycloakRealm;
    private final BootstrapAdminProperties properties;

    @Override
    public void run(String... args) {
        if (!properties.enabled()) {
            return;
        }

        if (userRepository.existsByRole_Name(RoleConstants.ADMIN)) {
            syncBootstrapAdminPhone();
            log.info("Bootstrap admin skipped, an account with role {} already exists", RoleConstants.ADMIN);
            return;
        }

        Role adminRole = roleRepository
                .findByName(RoleConstants.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Missing default role: " + RoleConstants.ADMIN));

        try {
            String userId = findOrCreateKeycloakAdmin();
            persistAdmin(userId, adminRole);
            log.warn("Bootstrap admin '{}' is ready with the configured password. Change it after the first login.",
                    properties.username());
        } catch (RuntimeException exception) {
            // Startup must not fail when Keycloak is unreachable, the seed retries on the next boot.
            log.error("Failed to seed the bootstrap admin account: {}", exception.getMessage());
        }
    }

    private String findOrCreateKeycloakAdmin() {
        String existingId = findKeycloakUserId();
        if (existingId != null) {
            updateKeycloakPhoneIfNeeded(existingId);
            log.info("Bootstrap admin already exists in Keycloak, reusing account {}", existingId);
            return existingId;
        }

        UserRepresentation user = new UserRepresentation();
        user.setUsername(properties.username());
        user.setEmail(properties.email());
        user.setFirstName(properties.firstName());
        user.setLastName(properties.lastName());
        user.singleAttribute("phoneNumber", properties.phoneNumber());
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setRequiredActions(new ArrayList<>());
        user.setCredentials(List.of(buildPasswordCredential()));

        try (Response response = keycloakRealm.users().create(user)) {
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL
                    || response.getLocation() == null) {
                throw new IllegalStateException(
                        "Keycloak rejected bootstrap admin creation with status " + response.getStatus());
            }

            String path = response.getLocation().getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    private String findKeycloakUserId() {
        List<UserRepresentation> byUsername = keycloakRealm.users().searchByUsername(properties.username(), true);
        if (!byUsername.isEmpty()) {
            return byUsername.getFirst().getId();
        }

        List<UserRepresentation> byEmail = keycloakRealm.users().searchByEmail(properties.email(), true);
        return byEmail.isEmpty() ? null : byEmail.getFirst().getId();
    }

    private CredentialRepresentation buildPasswordCredential() {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(properties.password());
        credential.setTemporary(false);
        return credential;
    }

    /**
     * Writes the row directly instead of waiting for the Keycloak event, so the seed does not
     * depend on Kafka timing. Both paths converge: the consumer skips an existing row, and this
     * method promotes a row the consumer may have already created with the default role.
     */
    private void persistAdmin(String userId, Role adminRole) {
        User admin = userRepository.findById(userId).orElseGet(() -> {
            User created = new User();
            created.setId(userId);
            return created;
        });

        admin.setUsername(properties.username());
        admin.setEmail(properties.email());
        admin.setFirstName(properties.firstName());
        admin.setLastName(properties.lastName());
        admin.setPhone(properties.phoneNumber());
        admin.setRole(adminRole);
        admin.setActive(true);
        admin.setOnBoarded(false);

        UserContextHolder.set(new UserContext(userId, properties.email()));
        try {
            userRepository.save(admin);
        } finally {
            UserContextHolder.clear();
        }
    }

    private void syncBootstrapAdminPhone() {
        if (properties.phoneNumber() == null) {
            return;
        }

        try {
            String userId = findKeycloakUserId();
            if (userId == null) {
                return;
            }

            userRepository.findById(userId).ifPresent(admin -> {
                if (admin.getPhone() != null && !admin.getPhone().isBlank()) {
                    return;
                }

                admin.setPhone(properties.phoneNumber());
                UserContextHolder.set(new UserContext(userId, properties.email()));
                try {
                    userRepository.save(admin);
                    log.info("Synced bootstrap admin phone to database for user {}", userId);
                } finally {
                    UserContextHolder.clear();
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Could not sync bootstrap admin phone: {}", exception.getMessage());
        }
    }

    private void updateKeycloakPhoneIfNeeded(String userId) {
        if (properties.phoneNumber() == null) {
            return;
        }

        var userResource = keycloakRealm.users().get(userId);
        UserRepresentation user = userResource.toRepresentation();
        String currentPhone = user.getAttributes() == null
                ? null
                : user.getAttributes().getOrDefault("phoneNumber", List.of()).stream()
                        .findFirst()
                        .orElse(null);
        if (currentPhone != null && !currentPhone.isBlank()) {
            return;
        }

        user.singleAttribute("phoneNumber", properties.phoneNumber());
        userResource.update(user);
    }
}
