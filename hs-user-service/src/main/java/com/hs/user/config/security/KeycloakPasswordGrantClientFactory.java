package com.hs.user.config.security;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KeycloakPasswordGrantClientFactory {

    private final String serverUrl;
    private final String currentRealm;
    private final String clientId;

    public KeycloakPasswordGrantClientFactory(
            @Value("${keycloak.server-url}") String serverUrl,
            @Value("${keycloak.current-realm}") String currentRealm,
            @Value("${keycloak.client-id}") String clientId
    ) {
        this.serverUrl = serverUrl;
        this.currentRealm = currentRealm;
        this.clientId = clientId;
    }

    public Keycloak createPasswordGrantClient(String username, String password) {
        KeycloakBuilder builder = KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm(currentRealm)
                .clientId(clientId)
                .grantType(OAuth2Constants.PASSWORD)
                .username(username)
                .password(password);

        return builder.build();
    }
}

