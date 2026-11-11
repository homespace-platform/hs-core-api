package com.hs.payment.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.Instant;

public final class EthPaymentDtos {
    private EthPaymentDtos() { }

    public record Challenge(String challengeId, String message, Instant expiresAt) { }
    public record CreateQuote(@NotBlank String challengeId, @NotBlank String signature) { }
    public record Quote(String paymentId, String payer, String recipient, BigDecimal amountVnd,
                        BigDecimal ethVnd, String amountWei, Instant priceUpdatedAt, Instant expiresAt,
                        long chainId, String contractAddress, String signature) { }
    public record Settle(@NotBlank String paymentId, @NotBlank String txHash) { }
    public record Status(String orderStatus, String paymentId, String txHash,
                         String amountWei, Instant expiresAt, Instant confirmedAt) { }
}
