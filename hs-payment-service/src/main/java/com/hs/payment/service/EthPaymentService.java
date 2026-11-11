package com.hs.payment.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.membership.MembershipService;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.EthPaymentDtos;
import com.hs.payment.membership.MembershipCheckoutService;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.membership.MembershipOrderRepository;
import com.hs.payment.model.EthPaymentQuote;
import com.hs.payment.model.EthWalletChallenge;
import com.hs.payment.repository.EthPaymentQuoteRepository;
import com.hs.payment.repository.EthWalletChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EthPaymentService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MembershipOrderRepository orders;
    private final MembershipService memberships;
    private final EthWalletChallengeRepository challenges;
    private final EthPaymentQuoteRepository quotes;
    private final EthVndPriceService prices;
    private final EthPaymentChainClient chain;
    private final EthPaymentSignatures signatures;

    @Value("${homespace.eth-payment.recipient-address:}") private String recipientAddress;

    @Transactional
    public EthPaymentDtos.Challenge challenge(String orderId, String ownerId, String wallet) {
        MembershipOrder order = payable(orderId, ownerId);
        if (!EthPaymentChainClient.address(wallet)) throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        EthWalletChallenge challenge = new EthWalletChallenge();
        challenge.setId(UUID.randomUUID().toString());
        challenge.setOrderId(order.getId());
        challenge.setPayerId(ownerId);
        challenge.setWallet(wallet.toLowerCase(Locale.ROOT));
        challenge.setExpiresAt(Instant.now().plus(Duration.ofMinutes(5)));
        challenge.setMessage("HomeSpace Sepolia membership wallet ownership\nOrder: " + order.getId()
                + "\nOwner: " + ownerId + "\nWallet: " + challenge.getWallet()
                + "\nNonce: " + challenge.getId() + "\nExpires at: " + challenge.getExpiresAt());
        challenges.save(challenge);
        return new EthPaymentDtos.Challenge(challenge.getId(), challenge.getMessage(), challenge.getExpiresAt());
    }

    @Transactional
    public EthPaymentDtos.Quote quote(String orderId, String ownerId, EthPaymentDtos.CreateQuote request) {
        memberships.lockOwner(ownerId);
        MembershipOrder order = orders.lockById(orderId)
                .orElseThrow(() -> new AppException(ListingErrorCode.MEMBERSHIP_ORDER_NOT_FOUND));
        requirePayable(order, ownerId);
        EthWalletChallenge challenge = challenges.findByIdForUpdate(request.challengeId())
                .orElseThrow(() -> new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID));
        if (!orderId.equals(challenge.getOrderId()) || !ownerId.equals(challenge.getPayerId())
                || challenge.getConsumedAt() != null || !Instant.now().isBefore(challenge.getExpiresAt())
                || !EthPaymentSignatures.ownsWallet(challenge.getMessage(), request.signature(), challenge.getWallet()))
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        var existing = quotes.findFirstByOrderIdOrderByExpiresAtDesc(orderId);
        if (existing.isPresent()) {
            EthPaymentQuote previous = existing.get();
            if (previous.getConfirmedAt() != null)
                throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
            if (Instant.now().isBefore(previous.getExpiresAt())) {
                if (!previous.getPayerWallet().equalsIgnoreCase(challenge.getWallet()))
                    throw new AppException(PaymentErrorCode.ETH_QUOTE_ALREADY_ISSUED);
                challenge.setConsumedAt(Instant.now());
                challenges.save(challenge);
                return response(previous);
            }
            chain.requireFinalizedUnpaid(previous);
        }
        if (!EthPaymentChainClient.address(recipientAddress))
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_CONFIGURED);
        String contract = chain.contractAddress();
        if (recipientAddress.equalsIgnoreCase(contract))
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_CONFIGURED);
        chain.requireQuoteSigner(signatures.signerAddress());
        Instant now = Instant.now();
        var price = prices.currentPrice(now);
        BigInteger wei = EthVndPriceService.weiFor(BigDecimal.valueOf(order.getAmountVnd()), price.ethVnd());
        EthPaymentQuote quote = new EthPaymentQuote();
        byte[] id = new byte[32];
        RANDOM.nextBytes(id);
        quote.setPaymentId(Numeric.toHexString(id));
        quote.setOrderId(order.getId());
        quote.setPayerWallet(challenge.getWallet());
        quote.setRecipientWallet(recipientAddress.toLowerCase(Locale.ROOT));
        quote.setContractAddress(contract);
        quote.setAmountVnd(BigDecimal.valueOf(order.getAmountVnd()));
        quote.setEthVnd(price.ethVnd());
        quote.setAmountWei(wei.toString());
        quote.setPriceUpdatedAt(price.updatedAt());
        Instant expiresAt = now.plus(Duration.ofMinutes(10));
        if (order.getExpiresAt().isBefore(expiresAt)) expiresAt = order.getExpiresAt();
        if (order.isUpgrade() && order.getUpgradeDeadline() != null
                && order.getUpgradeDeadline().minusNanos(1).isBefore(expiresAt))
            expiresAt = order.getUpgradeDeadline().minusNanos(1);
        if (!now.isBefore(expiresAt)) throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        quote.setExpiresAt(expiresAt);
        quote.setSignature(signatures.sign(quote, contract));
        quotes.save(quote);
        challenge.setConsumedAt(now);
        challenges.save(challenge);
        return response(quote);
    }

    @Transactional(readOnly = true)
    public EthPaymentDtos.Status status(String orderId, String ownerId) {
        MembershipOrder order = orders.findById(orderId)
                .orElseThrow(() -> new AppException(ListingErrorCode.MEMBERSHIP_ORDER_NOT_FOUND));
        if (!ownerId.equals(order.getOwnerId())) throw new AppException(ListingErrorCode.LISTING_FORBIDDEN);
        if (order.getProvider() != MembershipOrder.Provider.ETH)
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        return quotes.findFirstByOrderIdOrderByExpiresAtDesc(orderId)
                .map(quote -> new EthPaymentDtos.Status(order.getStatus().name(), quote.getPaymentId(),
                        quote.getTransactionHash(), quote.getAmountWei(), quote.getExpiresAt(), quote.getConfirmedAt()))
                .orElse(new EthPaymentDtos.Status(order.getStatus().name(), null, null, null, null, null));
    }

    @Transactional
    public MembershipCheckoutService.OrderView settle(String orderId, String ownerId, EthPaymentDtos.Settle request) {
        MembershipOrder preview = orders.findById(orderId)
                .orElseThrow(() -> new AppException(ListingErrorCode.MEMBERSHIP_ORDER_NOT_FOUND));
        if (!ownerId.equals(preview.getOwnerId())) throw new AppException(ListingErrorCode.LISTING_FORBIDDEN);
        if (preview.getProvider() != MembershipOrder.Provider.ETH)
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        EthPaymentQuote quote = quotes.findById(request.paymentId())
                .orElseThrow(() -> new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID));
        if (!orderId.equals(quote.getOrderId()) || request.txHash() == null
                || !request.txHash().matches("0x[0-9a-fA-F]{64}"))
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        String txHash = request.txHash().toLowerCase(Locale.ROOT);
        if (quote.getConfirmedAt() != null) {
            if (txHash.equals(quote.getTransactionHash())) return MembershipCheckoutService.OrderView.from(preview);
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        }
        Instant minedAt = chain.verify(quote, txHash);
        memberships.lockOwner(ownerId);
        MembershipOrder order = orders.lockById(orderId)
                .orElseThrow(() -> new AppException(ListingErrorCode.MEMBERSHIP_ORDER_NOT_FOUND));
        quote = quotes.findByIdForUpdate(request.paymentId())
                .orElseThrow(() -> new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID));
        if (quote.getConfirmedAt() != null) {
            if (txHash.equals(quote.getTransactionHash())) return MembershipCheckoutService.OrderView.from(order);
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        }
        if (quotes.existsByTransactionHash(txHash) || !orderId.equals(quote.getOrderId())
                || order.getAmountVnd() != quote.getAmountVnd().intValueExact())
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        Instant now = Instant.now();
        order.setStatus(order.getStatus() == MembershipOrder.Status.PENDING
                && memberships.activateAt(ownerId, order.getTier(), order.isUpgrade(), minedAt)
                ? MembershipOrder.Status.PAID : MembershipOrder.Status.REVIEW);
        order.setTransactionNo(txHash.substring(2));
        order.setPaidAt(now);
        quote.setTransactionHash(txHash);
        quote.setConfirmedAt(now);
        orders.save(order);
        quotes.save(quote);
        return MembershipCheckoutService.OrderView.from(order);
    }

    private MembershipOrder payable(String orderId, String ownerId) {
        MembershipOrder order = orders.findById(orderId)
                .orElseThrow(() -> new AppException(ListingErrorCode.MEMBERSHIP_ORDER_NOT_FOUND));
        requirePayable(order, ownerId);
        return order;
    }

    private static void requirePayable(MembershipOrder order, String ownerId) {
        if (!ownerId.equals(order.getOwnerId())) throw new AppException(ListingErrorCode.LISTING_FORBIDDEN);
        if (order.getProvider() != MembershipOrder.Provider.ETH
                || order.getStatus() != MembershipOrder.Status.PENDING
                || !Instant.now().isBefore(order.getExpiresAt()))
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
    }

    private static EthPaymentDtos.Quote response(EthPaymentQuote quote) {
        return new EthPaymentDtos.Quote(quote.getPaymentId(), quote.getPayerWallet(), quote.getRecipientWallet(),
                quote.getAmountVnd(), quote.getEthVnd(), quote.getAmountWei(), quote.getPriceUpdatedAt(),
                quote.getExpiresAt(), 11155111L, quote.getContractAddress(), quote.getSignature());
    }
}
