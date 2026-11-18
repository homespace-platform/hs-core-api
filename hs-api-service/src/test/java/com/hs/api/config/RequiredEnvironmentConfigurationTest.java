package com.hs.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequiredEnvironmentConfigurationTest {
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
}
