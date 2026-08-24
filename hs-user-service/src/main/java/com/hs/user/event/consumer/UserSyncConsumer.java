package com.hs.user.event.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hs.user.constant.RoleConstants;
import com.hs.common.context.UserContextHolder;
import com.hs.common.context.UserContext;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserSyncConsumer {

    UserRepository userRepository;
    AddressRepository addressRepository;
    RoleRepository roleRepository;
    ObjectMapper objectMapper;

    @KafkaListener(topics = "${spring.kafka.topic.keycloak-user-events}")
    @Transactional
    public void userSyncListener(String msg) {
        try {
            JsonNode payload = objectMapper.readTree(msg);
            String action = payload.path("action").asText("UNKNOWN");
            String source = payload.path("source").asText("UNKNOWN");
            String userId = payload.path("userId").asText();

            log.info("[KAFKA] [{}] Source: {} | UserID: {} | Email: {}",
                    String.format("%-6s", action), String.format("%-5s", source),
                    userId, payload.path("email").asText());

            try {
                UserContextHolder.set(new UserContext(userId, payload.path("email").asText(null)));

                switch (action) {
                    case "CREATE" -> handleCreateUser(payload, userId);
                    case "DELETE" -> handleDeleteUser(userId);
                    case "UPDATE" -> handleUpdateUser(payload, userId);
                    default -> log.warn("Unrecognized action: {}", action);
                }
            } finally {
                UserContextHolder.clear();
            }

        } catch (Exception e) {
            log.error("Failed to process Kafka message: {}", e.getMessage());
        }
    }

    private void handleCreateUser(JsonNode payload, String userId) {
        try {
            if (userRepository.existsById(userId)) {
                log.warn("User {} already exists. Skipping.", userId);
                return;
            }

            User newUser = new User();
            newUser.setId(userId);
            newUser.setUsername(payload.path("username").asText(userId));
            newUser.setEmail(payload.path("email").asText(null));
            newUser.setFirstName(payload.path("firstName").asText(null));
            newUser.setLastName(payload.path("lastName").asText(null));
            if (payload.has("avatarUrl")) newUser.setAvatarUrl(nullableText(payload, "avatarUrl"));
            if (payload.has("phoneNumber")) newUser.setPhone(nullableText(payload, "phoneNumber"));
            if (payload.hasNonNull("enabled")) newUser.setActive(payload.path("enabled").asBoolean());
            newUser.setRole(resolveDefaultRole());
            userRepository.save(newUser);

            log.info("Successfully persisted new user to DB.");
        } catch (Exception e) {
            log.error("Error saving user: {}", e.getMessage());
            throw e;
        }
    }

    private void handleUpdateUser(JsonNode payload, String userId) {
        userRepository.findById(userId).ifPresentOrElse(user -> {
            if (payload.has("username")) user.setUsername(payload.path("username").asText(null));
            if (payload.has("email")) user.setEmail(payload.path("email").asText(null));
            if (payload.has("firstName")) user.setFirstName(payload.path("firstName").asText(null));
            if (payload.has("lastName")) user.setLastName(payload.path("lastName").asText(null));
            if (payload.has("avatarUrl")) user.setAvatarUrl(nullableText(payload, "avatarUrl"));
            if (payload.has("phoneNumber")) user.setPhone(nullableText(payload, "phoneNumber"));

            if (payload.hasNonNull("enabled")) {
                user.setActive(payload.path("enabled").asBoolean());
            }

            userRepository.save(user);
            log.info("Successfully updated user {} from Kafka event.", userId);
        }, () -> log.warn("User {} not found. Skipping update.", userId));
    }

    private void handleDeleteUser(String userId) {
        userRepository.findById(userId).ifPresentOrElse(user -> {
            user.setActive(false);
            userRepository.save(user);
            addressRepository.findByUser_IdAndActiveTrue(userId).ifPresent(address -> {
                address.setActive(false);
                addressRepository.save(address);
            });
            log.info("Soft-deleted user {} from DB.", userId);
        }, () -> log.warn("User {} not found. Skipping delete.", userId));
    }

    private Role resolveDefaultRole() {
        return roleRepository
                .findByName(RoleConstants.USER)
                .orElseThrow(() -> new IllegalStateException("Missing default role: " + RoleConstants.USER));
    }

    private String nullableText(JsonNode payload, String fieldName) {
        JsonNode value = payload.get(fieldName);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}

