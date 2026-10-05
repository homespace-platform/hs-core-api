package com.hs.common.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/** Simulated billing time; authentication, SmartCA and storage continue on real time. */
@Component
@Slf4j
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
            fixed = parseSimulatedAt(simulatedAt.trim());
            log.info("Billing simulated time: {} (Asia/Ho_Chi_Minh), UTC: {}",
                    fixed.atZone(ZONE).toLocalDateTime(), fixed);
        }
    }

    private static Instant parseSimulatedAt(String value) {
        try {
            // Explicit offsets (including Z) retain their original meaning.
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                // An offset-free value is entered in Vietnamese local time.
                return LocalDateTime.parse(value).atZone(ZONE).toInstant();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException(
                        "homespace.time.simulated-at phải là yyyy-MM-dd'T'HH:mm:ss (giờ Việt Nam) "
                                + "hoặc ISO-8601 có múi giờ, ví dụ 2026-11-03T17:01:00Z", ex);
            }
        }
    }

    public Instant now() { return fixed == null ? Instant.now() : fixed; }
    public LocalDate today() { return LocalDate.ofInstant(now(), ZONE); }
}
