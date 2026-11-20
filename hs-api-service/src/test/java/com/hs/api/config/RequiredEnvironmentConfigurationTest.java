package com.hs.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class RequiredEnvironmentConfigurationTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void discoveryFlagsAreReadFromEnvironment(boolean enabled) throws IOException {
        Properties properties = applicationProperties();
        String value = Boolean.toString(enabled);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SPRING_CLOUD_DISCOVERY_ENABLED", value)
                .withProperty("EUREKA_CLIENT_ENABLED", value);

        assertEquals(value, environment.resolveRequiredPlaceholders(
                properties.getProperty("spring.cloud.discovery.enabled")));
        assertEquals(value, environment.resolveRequiredPlaceholders(
                properties.getProperty("eureka.client.enabled")));
    }

    @Test
    void discoveryFlagsAreRequiredAndCannotBeBlank() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SPRING_CLOUD_DISCOVERY_ENABLED", "");

        assertTrue(RequiredEnvironmentConfiguration.missingVariables(applicationProperties(), environment)
                .containsAll(Set.of("SPRING_CLOUD_DISCOVERY_ENABLED", "EUREKA_CLIENT_ENABLED")));
    }

    @Test
    void startupFailsWhenApplicationVariableIsMissing() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new RequiredEnvironmentConfiguration(new MockEnvironment()));

        assertTrue(error.getMessage().contains("KAFKA_EVENTS_TOPIC"));
    }

    @Test
    void startupRequiresGatewayInternalSecret() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new RequiredEnvironmentConfiguration(new MockEnvironment()));

        assertTrue(error.getMessage().contains("GATEWAY_INTERNAL_SECRET"));
    }

    @Test
    void reportsMissingVariablesWithoutTheirValues() {
        Properties properties = new Properties();
        properties.setProperty("first", "${KAFKA_EVENTS_TOPIC}");
        properties.setProperty("second", "${VNPT_EKYC_CLIENT_SECRET}");
        properties.setProperty("optional", "${HOMESPACE_TIME_SIMULATED_AT}");
        properties.setProperty("optionalMissing", "${SMARTCA_WEBHOOK_PUBLIC_URL}");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("KAFKA_EVENTS_TOPIC", "private-value")
                .withProperty("VNPT_EKYC_CLIENT_SECRET", "")
                .withProperty("HOMESPACE_TIME_SIMULATED_AT", "");

        assertEquals(
                java.util.Set.of("VNPT_EKYC_CLIENT_SECRET"),
                RequiredEnvironmentConfiguration.missingVariables(properties, environment));
    }

    private Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing application.properties");
            }
            properties.load(input);
        }
        return properties;
    }
}
