package com.hs.common.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

/** Simulated billing time; authentication, SmartCA and storage continue on real time. */
@Component
public class BillingTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final Instant fixed;

    public BillingTime(@Value("${homespace.time.simulated-at:}") String simulatedAt,
                       Environment environment) {
        if (simulatedAt == null || simulatedAt.isBlank()) {
            fixed = null;
        } else {
            boolean dev = Arrays.stream(environment.getActiveProfiles())
                    .anyMatch(p -> p.equalsIgnoreCase("dev") || p.equalsIgnoreCase("test"));
            if (!dev) throw new IllegalStateException("homespace.time.simulated-at chỉ dùng trong dev/test");
            fixed = Instant.parse(simulatedAt.trim());
        }
    }

    public Instant now() { return fixed == null ? Instant.now() : fixed; }
    public LocalDate today() { return LocalDate.ofInstant(now(), ZONE); }
}
