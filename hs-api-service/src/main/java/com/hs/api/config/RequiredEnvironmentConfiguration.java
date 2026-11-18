package com.hs.api.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RequiredEnvironmentConfiguration {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");
    private static final Set<String> OPTIONAL_EMPTY = Set.of("HOMESPACE_TIME_SIMULATED_AT", "SMARTCA_WEBHOOK_PUBLIC_URL");

    public RequiredEnvironmentConfiguration(Environment environment) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing application.properties");
            }
            properties.load(input);
        }
        Set<String> missing = missingVariables(properties, environment);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing environment variables: " + String.join(", ", missing));
        }
    }

    static Set<String> missingVariables(Properties properties, Environment environment) {
        Set<String> missing = new TreeSet<>();
        for (Object value : properties.values()) {
            Matcher matcher = VARIABLE.matcher(value.toString());
            while (matcher.find()) {
                String variable = matcher.group(1);
                String configuredValue = environment.getProperty(variable);
                if ((configuredValue == null || configuredValue.isBlank()) && !OPTIONAL_EMPTY.contains(variable)) {
                    missing.add(variable);
                }
            }
        }
        return missing;
    }
}
