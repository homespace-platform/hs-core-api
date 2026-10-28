package com.hs.listing.nearby;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.PropertiesPropertySource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class NearbyConfigurationTest {
    static Properties applicationProperties() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("hs-api-service/src/main/resources/application.properties"))) root = root.getParent();
        assertNotNull(root, "Core application.properties must exist");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("hs-api-service/src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception error) { throw new IllegalStateException(error); }
        // Offline fixture: never load the developer's .env or system credentials.
        properties.setProperty("TOMTOM_API_KEY", "offline-test-only");
        return properties;
    }

    static Properties properties() {
        Properties properties = applicationProperties();
        properties.setProperty("nearby.sync-enabled", "false");
        properties.setProperty("nearby.query-enabled", "false");
        properties.setProperty("nearby.tomtom.license-approved", "false");
        properties.setProperty("nearby.tomtom.license-policy-id", "offline-test-policy");
        return properties;
    }

    static NearbySettings configured() { return configured(properties()); }

    private static NearbySettings configured(Properties properties) {
        try (var context = new AnnotationConfigApplicationContext()) {
            var sources = context.getEnvironment().getPropertySources();
            sources.remove("systemProperties");
            sources.remove("systemEnvironment");
            sources.addFirst(new PropertiesPropertySource("explicit-test-configuration", properties));
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            context.register(NearbySettings.class);
            context.refresh();
            return context.getBean(NearbySettings.class);
        }
    }

    @Test void everyNearbyPropertyIsRequiredEvenWhenSyncIsDisabled() {
        for (var field : NearbySettings.class.getDeclaredFields()) {
            Value annotation = field.getAnnotation(Value.class);
            if (annotation == null) continue;
            String key = annotation.value().substring(2, annotation.value().length() - 1);
            assertFalse(key.contains(":"), "No fallback in @Value: " + key);
            Properties missing = properties();
            missing.remove(key);
            assertThrows(BeanCreationException.class, () -> configured(missing), key);
        }
        Properties missingSecret = properties();
        missingSecret.remove("TOMTOM_API_KEY");
        assertThrows(BeanCreationException.class, () -> configured(missingSecret));
    }

    @Test void invalidBusinessConfigurationFailsDuringBeanCreation() {
        Properties invalid = properties();
        invalid.setProperty("nearby.default-radius-m", "0");
        assertThrows(BeanCreationException.class, () -> configured(invalid));
    }

    @Test void explicitPropertiesConfigureTheDisabledModuleWithoutCallingAProvider() {
        NearbySettings settings = configured();
        assertFalse(settings.readable());
        assertFalse(settings.runnable());
        assertEquals(Integer.parseInt(properties().getProperty("nearby.default-radius-m")), settings.radius(null));
    }

    @Test void shippedConfigurationEnablesAutomaticSyncAndQueries() {
        NearbySettings settings = configured(applicationProperties());
        assertTrue(settings.runnable());
        assertTrue(settings.readable());
        assertEquals(2000,settings.radius(null));
        assertEquals(2000,settings.getScanRadiusM());
        assertEquals(5,settings.getPublicPageSize());
    }
}
