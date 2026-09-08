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

    /** Fallback CCCD when env does not set {@code cccd} for the account. */
    public static final String DEFAULT_BOOTSTRAP_ADMIN_CCCD = "075999999999";
    public static final String DEFAULT_BOOTSTRAP_ADMIN_CCCD_2 = "075999999998";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RealmResource keycloakRealm;
    private final BootstrapAdminProperties properties;

    @Override
    public void run(String... args) {
        List<BootstrapAdminAccount> accounts = properties.enabledAdmins();
        if (accounts.isEmpty()) {
            return;
        }

        Role adminRole = roleRepository
                .findByName(RoleConstants.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Missing default role: " + RoleConstants.ADMIN));

        for (int i = 0; i < accounts.size(); i++) {
            BootstrapAdminAccount account = accounts.get(i);
            String cccd = resolveCccd(account, i);
            try {
                seedOrSyncAdmin(account, adminRole, cccd);
            } catch (RuntimeException exception) {
                log.error("Failed to seed bootstrap admin '{}': {}", account.username(), exception.getMessage());
            }
        }
    }

    private void seedOrSyncAdmin(BootstrapAdminAccount account, Role adminRole, String cccd) {
        if (account.username() == null || account.email() == null || account.password() == null) {
            log.warn("Skipping bootstrap admin — username/email/password required");
            return;
        }

        String userId = findOrCreateKeycloakAdmin(account);
        if (userRepository.existsById(userId)) {
            syncPhone(userId, account);
            syncCccd(userId, account.email(), cccd);
            ensureAdminRole(userId, adminRole, account.email());
            log.info("Bootstrap admin '{}' already present — synced role/phone/cccd", account.username());
            return;
        }

        persistAdmin(userId, account, adminRole, cccd);
        log.warn("Bootstrap admin '{}' is ready with the configured password. Change it after the first login.",
                account.username());
    }

    private static String resolveCccd(BootstrapAdminAccount account, int index) {
        if (account.cccd() != null && !account.cccd().isBlank()) {
            return account.cccd().trim();
        }
        return index == 0 ? DEFAULT_BOOTSTRAP_ADMIN_CCCD : DEFAULT_BOOTSTRAP_ADMIN_CCCD_2;
    }

    private String findOrCreateKeycloakAdmin(BootstrapAdminAccount account) {
        String existingId = findKeycloakUserId(account.username(), account.email());
        if (existingId != null) {
            updateKeycloakPhoneIfNeeded(existingId, account.phoneNumber());
            log.info("Bootstrap admin already exists in Keycloak, reusing account {}", existingId);
            return existingId;
        }

        UserRepresentation user = new UserRepresentation();
        user.setUsername(account.username());
        user.setEmail(account.email());
        user.setFirstName(account.firstName());
        user.setLastName(account.lastName());
        if (account.phoneNumber() != null) {
            user.singleAttribute("phoneNumber", account.phoneNumber());
        }
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setRequiredActions(new ArrayList<>());
        user.setCredentials(List.of(buildPasswordCredential(account.password())));

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

    private String findKeycloakUserId(String username, String email) {
        List<UserRepresentation> byUsername = keycloakRealm.users().searchByUsername(username, true);
        if (!byUsername.isEmpty()) {
            return byUsername.getFirst().getId();
        }

        List<UserRepresentation> byEmail = keycloakRealm.users().searchByEmail(email, true);
        return byEmail.isEmpty() ? null : byEmail.getFirst().getId();
    }

    private static CredentialRepresentation buildPasswordCredential(String password) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        return credential;
    }

    private void persistAdmin(String userId, BootstrapAdminAccount account, Role adminRole, String cccd) {
        User admin = userRepository.findById(userId).orElseGet(() -> {
            User created = new User();
            created.setId(userId);
            return created;
        });

        admin.setUsername(account.username());
        admin.setEmail(account.email());
        admin.setFirstName(account.firstName());
        admin.setLastName(account.lastName());
        admin.setPhone(account.phoneNumber());
        if (cccd != null && !userRepository.existsByCccdAndIdNot(cccd, userId)) {
            admin.setCccd(cccd);
        }
        admin.setRole(adminRole);
        admin.setActive(true);
        admin.setOnBoarded(false);

        UserContextHolder.set(new UserContext(userId, account.email()));
        try {
            userRepository.save(admin);
        } finally {
            UserContextHolder.clear();
        }
    }

    private void ensureAdminRole(String userId, Role adminRole, String email) {
        userRepository.findById(userId).ifPresent(admin -> {
            if (admin.getRole() != null && RoleConstants.ADMIN.equals(admin.getRole().getName())) {
                return;
            }
            admin.setRole(adminRole);
            UserContextHolder.set(new UserContext(userId, email));
            try {
                userRepository.save(admin);
            } finally {
                UserContextHolder.clear();
            }
        });
    }

    private void syncPhone(String userId, BootstrapAdminAccount account) {
        if (account.phoneNumber() == null) {
            return;
        }

        try {
            userRepository.findById(userId).ifPresent(admin -> {
                if (admin.getPhone() != null && !admin.getPhone().isBlank()) {
                    return;
                }

                admin.setPhone(account.phoneNumber());
                UserContextHolder.set(new UserContext(userId, account.email()));
                try {
                    userRepository.save(admin);
                    log.info("Synced bootstrap admin phone for user {}", userId);
                } finally {
                    UserContextHolder.clear();
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Could not sync bootstrap admin phone: {}", exception.getMessage());
        }
    }

    private void syncCccd(String userId, String email, String cccd) {
        if (cccd == null || cccd.isBlank()) {
            return;
        }

        try {
            userRepository.findById(userId).ifPresent(admin -> {
                if (cccd.equals(admin.getCccd())) {
                    return;
                }
                if (admin.getCccd() != null && !admin.getCccd().isBlank()) {
                    return;
                }
                if (userRepository.existsByCccdAndIdNot(cccd, userId)) {
                    log.warn("Cannot seed bootstrap admin CCCD {} — already used", cccd);
                    return;
                }

                admin.setCccd(cccd);
                UserContextHolder.set(new UserContext(userId, email));
                try {
                    userRepository.save(admin);
                    log.info("Synced bootstrap admin CCCD for user {}", userId);
                } finally {
                    UserContextHolder.clear();
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Could not sync bootstrap admin CCCD: {}", exception.getMessage());
        }
    }

    private void updateKeycloakPhoneIfNeeded(String userId, String phoneNumber) {
        if (phoneNumber == null) {
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

        user.singleAttribute("phoneNumber", phoneNumber);
        userResource.update(user);
    }
}
