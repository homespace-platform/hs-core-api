package com.hs.user.config.security;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KeycloakAdminClientConfig {

    @Bean
    Keycloak keycloakAdminClient(
            @Value("${keycloak.server-url}") String serverUrl,
            @Value("${keycloak.admin.realm}") String adminRealm,
            @Value("${keycloak.admin.client-id}") String adminClientId,
            @Value("${keycloak.admin.user-name}") String username,
            @Value("${keycloak.admin.password}") String password
    ) {
        return KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm(adminRealm)
                .clientId(adminClientId)
                .username(username)
                .password(password)
                .grantType(OAuth2Constants.PASSWORD)
                .build();
    }

    @Bean
    RealmResource keycloakRealm(
            Keycloak keycloakAdminClient,
            @Value("${keycloak.current-realm}") String currentRealm) {
        return keycloakAdminClient.realm(currentRealm);
    }
}

