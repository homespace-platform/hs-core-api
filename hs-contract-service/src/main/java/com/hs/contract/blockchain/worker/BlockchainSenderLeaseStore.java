package com.hs.contract.blockchain.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BlockchainSenderLeaseStore {
    private final JdbcTemplate jdbc;

    public boolean acquire(String id, String token) {
        return jdbc.update("""
                INSERT INTO blockchain_sender_leases(id, owner_token, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                ON CONFLICT(id) DO UPDATE SET owner_token = EXCLUDED.owner_token, expires_at = EXCLUDED.expires_at
                WHERE blockchain_sender_leases.expires_at < CURRENT_TIMESTAMP
                """, id, token) == 1;
    }

    /** Phải gọi trong transaction ngắn, trước khi khóa và cập nhật proof. */
    public boolean ownsForUpdate(String id, String token) {
        return !jdbc.queryForList("""
                SELECT id FROM blockchain_sender_leases
                WHERE id = ? AND owner_token = ? AND expires_at > CURRENT_TIMESTAMP FOR UPDATE
                """, String.class, id, token).isEmpty();
    }

    public void release(String id, String token) {
        jdbc.update("DELETE FROM blockchain_sender_leases WHERE id = ? AND owner_token = ?", id, token);
    }
}
