package com.hs.user.config.database;

import com.hs.common.security.SecretCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Encrypts legacy plaintext KYC rows and fills cccd_hash. Idempotent (already "v1:" values are skipped),
 * batched, and safe with several instances: each batch locks its rows with FOR UPDATE SKIP LOCKED.
 * Never logs row content.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class KycEncryptionMigration implements CommandLineRunner {

    static final List<String> PII_COLUMNS = List.of("cccd", "full_name", "birth_date", "permanent_address",
            "gender", "issue_date", "expiration_date", "issue_place");
    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(String... args) {
        long total = 0;
        int done;
        do {
            done = new TransactionTemplate(transactionManager).execute(status -> migrateBatch());
            total += done;
        } while (done > 0);
        if (total > 0) {
            log.info("Encrypted {} legacy KYC rows", total);
        }
    }

    private int migrateBatch() {
        SecretCrypto crypto = SecretCrypto.get();
        StringBuilder needs = new StringBuilder("(cccd IS NOT NULL AND BTRIM(cccd) <> '' AND cccd_hash IS NULL)");
        for (String c : PII_COLUMNS) {
            needs.append(" OR (").append(c).append(" IS NOT NULL AND BTRIM(").append(c).append(") <> '' AND ")
                    .append(c).append(" NOT LIKE 'v1:%')");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id, " + String.join(", ", PII_COLUMNS)
                + " FROM user_kyc_details WHERE " + needs + " ORDER BY user_id LIMIT " + BATCH
                + " FOR UPDATE SKIP LOCKED");
        for (Map<String, Object> row : rows) {
            List<Object> args = new ArrayList<>();
            StringBuilder set = new StringBuilder();
            for (String c : PII_COLUMNS) {
                String value = (String) row.get(c);
                if (value == null || value.isBlank() || SecretCrypto.isKycCiphertext(value)) {
                    continue;
                }
                set.append(set.length() == 0 ? "" : ", ").append(c).append(" = ?");
                args.add(crypto.encryptKyc(value));
            }
            String cccd = (String) row.get("cccd");
            if (cccd != null && !cccd.isBlank()) {
                String plain = SecretCrypto.isKycCiphertext(cccd) ? crypto.decryptKyc(cccd) : cccd;
                set.append(set.length() == 0 ? "" : ", ").append("cccd_hash = ?");
                args.add(crypto.cccdHash(plain));
            }
            args.add(row.get("user_id"));
            jdbc.update("UPDATE user_kyc_details SET " + set + " WHERE user_id = ?", args.toArray());
        }
        return rows.size();
    }
}
