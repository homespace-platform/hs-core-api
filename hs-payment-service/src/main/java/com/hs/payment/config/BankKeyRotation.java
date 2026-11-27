package com.hs.payment.config;

import com.hs.common.security.SecretCrypto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Temporary HOMESPACE_ENCRYPTION_KEY rotation for bank_accounts.account_number. Runs only while
 * homespace.security.previous-encryption-key is set and differs from the current key; remove the env after one deploy.
 * Idempotent and safe with several instances (FOR UPDATE SKIP LOCKED). Never logs values. KYC / HMAC sub-keys are
 * untouched: they only ever come from SecretCrypto.get() (current key), the previous key is used for bank decrypt only.
 */
@Component @Slf4j @Order(2)
public class BankKeyRotation implements CommandLineRunner {
    static final int BATCH = 100;
    private static final int MIN_CIPHERTEXT_BYTES = 12 + 16 + 1; // same shape rule as BankAccountCryptoConverter
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String previousKey;
    private final String currentKey;

    public BankKeyRotation(JdbcTemplate jdbc, PlatformTransactionManager txManager,
            @Value("${homespace.security.previous-encryption-key:}") String previousKey,
            @Value("${homespace.security.encryption-key}") String currentKey) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(txManager); this.previousKey = previousKey; this.currentKey = currentKey;
    }

    @Override
    public void run(String... args) {
        if (previousKey == null || previousKey.isBlank() || previousKey.equals(currentKey)) return;
        SecretCrypto current = SecretCrypto.of(currentKey), previous = SecretCrypto.of(previousKey);
        int[] total = new int[3];
        String after = "";
        while (true) {
            final String cursor = after;
            // One tx per batch: the row locks live until the updates commit. Rows locked by another instance are skipped.
            var done = tx.execute(s -> rotateBatch(current, previous, cursor));
            for (int i = 0; i < 3; i++) total[i] += done.counts()[i];
            if (done.size() < BATCH) break;
            after = done.lastId();
        }
        log.info("Bank key rotation: rotated={}, alreadyCurrent={}, undecryptable={}", total[0], total[1], total[2]);
    }

    private record Batch(int size, String lastId, int[] counts) {}

    private Batch rotateBatch(SecretCrypto current, SecretCrypto previous, String cursor) {
        int[] c = new int[3];
        var rows = jdbc.query(
                "SELECT id, account_number FROM bank_accounts WHERE id > ? ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, cursor, BATCH);
        for (var r : rows) {
            if (!looksLikeCiphertext(r[1])) continue; // legacy plaintext: not ours
            if (decrypts(current, r[1]) != null) { c[1]++; continue; }
            String plain = decrypts(previous, r[1]);
            if (plain == null) { c[2]++; log.error("Bank account {} decrypts with neither current nor previous key; left unchanged", r[0]); continue; }
            jdbc.update("UPDATE bank_accounts SET account_number = ? WHERE id = ?", current.encryptBank(plain), r[0]);
            c[0]++;
        }
        return new Batch(rows.size(), rows.isEmpty() ? cursor : rows.get(rows.size() - 1)[0], c);
    }

    private static String decrypts(SecretCrypto key, String stored) {
        try { return key.decryptBank(stored); } catch (GeneralSecurityException | IllegalArgumentException e) { return null; }
    }

    private static boolean looksLikeCiphertext(String v) {
        try { return v != null && Base64.getDecoder().decode(v).length >= MIN_CIPHERTEXT_BYTES; }
        catch (IllegalArgumentException e) { return false; }
    }
}
