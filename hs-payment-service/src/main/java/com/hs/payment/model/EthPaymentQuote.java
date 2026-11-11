package com.hs.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "membership_eth_quotes", indexes = @Index(name = "idx_membership_eth_quote_order", columnList = "membership_order_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_membership_eth_quote_tx", columnNames = "transaction_hash"))
@Getter
@Setter
public class EthPaymentQuote {
    @Id
    @Column(name = "payment_id", length = 66, updatable = false)
    private String paymentId;

    @Column(name = "membership_order_id", nullable = false, length = 32, updatable = false)
    private String orderId;

    @Column(name = "payer_wallet", nullable = false, length = 42, updatable = false)
    private String payerWallet;

    @Column(name = "recipient_wallet", nullable = false, length = 42, updatable = false)
    private String recipientWallet;

    @Column(name = "contract_address", nullable = false, length = 42, updatable = false)
    private String contractAddress;

    @Column(name = "amount_vnd", nullable = false, precision = 20, scale = 2, updatable = false)
    private BigDecimal amountVnd;

    @Column(name = "eth_vnd", nullable = false, precision = 30, scale = 10, updatable = false)
    private BigDecimal ethVnd;

    @Column(name = "amount_wei", nullable = false, length = 78, updatable = false)
    private String amountWei;

    @Column(name = "price_updated_at", nullable = false, updatable = false)
    private Instant priceUpdatedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "signature", nullable = false, length = 132, updatable = false)
    private String signature;

    @Column(name = "transaction_hash", length = 66)
    private String transactionHash;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;
}
