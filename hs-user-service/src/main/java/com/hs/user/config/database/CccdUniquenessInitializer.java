package com.hs.user.config.database;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Moves legacy citizen IDs to KYC, widens PII columns and enforces uniqueness on the CCCD blind index. */
@Component
@Order(0)
@RequiredArgsConstructor
public class CccdUniquenessInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(String... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('homespace.kyc.schema', 0))");
            if (legacyColumnExists()) {
                jdbc.execute("LOCK TABLE users, user_kyc_details IN SHARE ROW EXCLUSIVE MODE");
                Boolean duplicate = jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT cccd FROM (
                                SELECT NULLIF(BTRIM(cccd), '') AS cccd FROM user_kyc_details
                                UNION ALL
                                SELECT NULLIF(BTRIM(u.cccd), '') FROM users u
                                WHERE NOT EXISTS (SELECT 1 FROM user_kyc_details k
                                    WHERE k.user_id = u.id AND NULLIF(BTRIM(k.cccd), '') IS NOT NULL)
                            ) candidates
                            WHERE cccd IS NOT NULL GROUP BY cccd HAVING COUNT(*) > 1
                        )
                        """, Boolean.class);
                if (Boolean.TRUE.equals(duplicate)) {
                    throw new IllegalStateException("Duplicate citizen IDs prevent KYC migration; reconcile the records before restarting.");
                }
                jdbc.execute("""
                        INSERT INTO user_kyc_details (user_id, cccd)
                        SELECT id, BTRIM(cccd) FROM users WHERE NULLIF(BTRIM(cccd), '') IS NOT NULL
                        ON CONFLICT (user_id) DO UPDATE
                        SET cccd = COALESCE(NULLIF(BTRIM(user_kyc_details.cccd), ''), EXCLUDED.cccd)
                        """);
                jdbc.execute("ALTER TABLE users DROP COLUMN cccd");
            }
            // Ciphertext columns need room (ddl-auto=update never widens); idempotent.
            for (String column : KycEncryptionMigration.PII_COLUMNS) {
                jdbc.execute("ALTER TABLE user_kyc_details ALTER COLUMN " + column
                        + " TYPE text");
            }
            jdbc.execute("ALTER TABLE user_kyc_details ADD COLUMN IF NOT EXISTS cccd_hash varchar(64)");
            // Uniqueness now lives on the blind index; the old index on the (random) ciphertext is useless.
            jdbc.execute("ALTER TABLE user_kyc_details DROP CONSTRAINT IF EXISTS uk_user_kyc_details_cccd");
            jdbc.execute("DROP INDEX IF EXISTS uk_user_kyc_details_cccd");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_user_kyc_details_cccd_hash ON user_kyc_details (cccd_hash)");
        });
    }

    private boolean legacyColumnExists() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'cccd'",
                Long.class);
        return count != null && count > 0;
    }
}
