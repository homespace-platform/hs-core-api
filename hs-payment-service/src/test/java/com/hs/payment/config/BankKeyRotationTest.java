package com.hs.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.Mockito.*;

class BankKeyRotationTest {
    private void runWith(String previous, String current) {
        var jdbc = mock(JdbcTemplate.class);
        new BankKeyRotation(jdbc, mock(PlatformTransactionManager.class), previous, current).run();
        verifyNoInteractions(jdbc);
    }

    @Test void noOpWhenPreviousBlank() { runWith("", "new-key"); runWith("  ", "new-key"); }

    @Test void noOpWhenPreviousEqualsCurrent() { runWith("same-key", "same-key"); }
}
