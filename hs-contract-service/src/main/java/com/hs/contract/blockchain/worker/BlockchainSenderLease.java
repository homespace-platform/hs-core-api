package com.hs.contract.blockchain.worker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Lease dùng chung giữa các instance; không giữ transaction database trong lúc gọi RPC. */
@Entity
@Table(name = "blockchain_sender_leases")
public class BlockchainSenderLease {
    @Id @Column(length = 128) private String id;
    @Column(name = "owner_token", nullable = false, length = 36) private String ownerToken;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    protected BlockchainSenderLease() {}
}
