package com.hs.user.config.database;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Ensures CCCD is unique in PostgreSQL. */
@Component
@Order(0)
@RequiredArgsConstructor
public class CccdUniquenessInitializer implements CommandLineRunner {

    private static final String STANDARD_INDEX = "uk_users_cccd_standard";
    private static final String LEGACY_TEST_INDEX = "uk_users_cccd_test_admin_pair";
    private static final String LEGACY_TEST_CHECK = "ck_users_cccd_test_admin_pair";

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(String... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (constraintExists(LEGACY_TEST_CHECK)) {
                jdbc.execute("ALTER TABLE users DROP CONSTRAINT " + LEGACY_TEST_CHECK);
            }
            jdbc.execute("DROP INDEX IF EXISTS " + LEGACY_TEST_INDEX);
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + STANDARD_INDEX + " ON users (cccd)");
        });
    }

    private boolean constraintExists(String name) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.users'::regclass AND conname = ?",
                Long.class, name);
        return count != null && count > 0;
    }
}
