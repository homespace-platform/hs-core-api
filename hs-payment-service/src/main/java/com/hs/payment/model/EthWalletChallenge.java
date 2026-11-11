package com.hs.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "membership_eth_wallet_challenges")
@Getter
@Setter
public class EthWalletChallenge {
    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "membership_order_id", nullable = false, length = 32, updatable = false)
    private String orderId;

    @Column(name = "payer_id", nullable = false, length = 36, updatable = false)
    private String payerId;

    @Column(name = "wallet", nullable = false, length = 42, updatable = false)
    private String wallet;

    @Column(name = "message", nullable = false, length = 500, updatable = false)
    private String message;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
