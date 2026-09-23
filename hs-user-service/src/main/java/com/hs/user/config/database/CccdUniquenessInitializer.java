package com.hs.user.config.database;

import com.hs.user.service.impl.CccdTestSharingPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps CCCD unique in PostgreSQL, except for one explicitly configured dev test-admin pair. */
@Component
@Order(0)
@RequiredArgsConstructor
public class CccdUniquenessInitializer implements CommandLineRunner {

    private static final String TEST_INDEX = "uk_users_cccd_test_admin_pair";
    private static final String STANDARD_INDEX = "uk_users_cccd_standard";
    private static final String TEST_CHECK = "ck_users_cccd_test_admin_pair";

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final CccdTestSharingPolicy policy;

    @Override
    public void run(String... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (policy.isEnabled()) {
                enableTestSharing();
            } else {
                enforceStandardUniqueness();
            }
        });
    }

    private void enableTestSharing() {
        String cccd = literal(policy.sharedCccd());
        String first = literal(policy.firstUsername());
        String second = literal(policy.secondUsername());

        // Slot 1 belongs only to the second bootstrap admin; everyone else uses slot 0.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + TEST_INDEX
                + " ON users (cccd, (CASE WHEN cccd = " + cccd
                + " AND username = " + second + " THEN 1 ELSE 0 END))");
        jdbc.execute("DROP INDEX IF EXISTS " + STANDARD_INDEX);

        List<String> oldConstraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'public.users'::regclass "
                        + "AND contype = 'u' AND pg_get_constraintdef(oid) = 'UNIQUE (cccd)'",
                String.class);
        for (String constraint : oldConstraints) {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT \"" + constraint.replace("\"", "\"\"") + "\"");
        }

        if (!constraintExists(TEST_CHECK)) {
            jdbc.execute("ALTER TABLE users ADD CONSTRAINT " + TEST_CHECK
                    + " CHECK (cccd IS DISTINCT FROM " + cccd
                    + " OR username IN (" + first + ", " + second + "))");
        }
    }

    private void enforceStandardUniqueness() {
        // This fails closed if dev data still has duplicate CCCDs when test mode is switched off.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + STANDARD_INDEX + " ON users (cccd)");
        if (constraintExists(TEST_CHECK)) {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT " + TEST_CHECK);
        }
        jdbc.execute("DROP INDEX IF EXISTS " + TEST_INDEX);
    }

    private boolean constraintExists(String name) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.users'::regclass AND conname = ?",
                Long.class, name);
        return count != null && count > 0;
    }

    private static String literal(String value) {
        // Policy validates both CCCD and usernames; keep SQL literal escaping as defence in depth.
        return "'" + value.replace("'", "''") + "'";
    }
}
