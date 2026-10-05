package com.hs.common.time;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingTimeTest {

    @Test
    void localVietnameseTimeUsesVietnameseZone() {
        BillingTime time = new BillingTime("2026-11-04T00:01:00", environment("dev"));

        assertEquals(Instant.parse("2026-11-03T17:01:00Z"), time.now());
        assertEquals(LocalDate.of(2026, 11, 4), time.today());
    }

    @Test
    void explicitUtcAndOffsetRemainSupported() {
        Instant expected = Instant.parse("2026-11-03T17:01:00Z");

        assertEquals(expected, new BillingTime("2026-11-03T17:01:00Z", environment("dev")).now());
        assertEquals(expected, new BillingTime("2026-11-04T00:01:00+07:00", environment("test")).now());
    }

    @Test
    void simulatedTimeIsRejectedOutsideDevAndTest() {
        assertThrows(IllegalStateException.class,
                () -> new BillingTime("2026-11-04T00:01:00", environment("prod")));
    }

    @Test
    void malformedValueFailsWithAnActionableMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new BillingTime("04/11/2026 00:01", environment("dev")));

        assertTrue(error.getMessage().contains("giờ Việt Nam"));
    }

    private static MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
