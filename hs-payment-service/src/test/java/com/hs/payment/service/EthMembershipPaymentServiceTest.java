package com.hs.payment.service;

import com.hs.listing.membership.MembershipService;
import com.hs.listing.membership.MembershipTier;
import com.hs.payment.dto.EthPaymentDtos;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.membership.MembershipOrderRepository;
import com.hs.payment.model.EthPaymentQuote;
import com.hs.payment.repository.EthPaymentQuoteRepository;
import com.hs.payment.repository.EthWalletChallengeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EthMembershipPaymentServiceTest {
    private final MembershipOrderRepository orders = mock(MembershipOrderRepository.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final EthWalletChallengeRepository challenges = mock(EthWalletChallengeRepository.class);
    private final EthPaymentQuoteRepository quotes = mock(EthPaymentQuoteRepository.class);
    private final EthVndPriceService prices = mock(EthVndPriceService.class);
    private final EthPaymentChainClient chain = mock(EthPaymentChainClient.class);
    private final EthPaymentSignatures signatures = mock(EthPaymentSignatures.class);
    private final EthPaymentService service = new EthPaymentService(orders, memberships, challenges, quotes,
            prices, chain, signatures);

    @Test
    void verifiedEthPaymentActivatesMembershipOnceAndStoresFullHashInQuote() {
        MembershipOrder order = order();
        EthPaymentQuote quote = quote();
        String txHash = "0x" + "a".repeat(64);
        Instant minedAt = Instant.now().minusSeconds(30);
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        when(quotes.findById(quote.getPaymentId())).thenReturn(Optional.of(quote));
        when(quotes.findByIdForUpdate(quote.getPaymentId())).thenReturn(Optional.of(quote));
        when(chain.verify(quote, txHash)).thenReturn(minedAt);
        when(memberships.activateAt("owner", MembershipTier.STARTER, false, minedAt)).thenReturn(true);

        var first = service.settle("order1", "owner", new EthPaymentDtos.Settle(quote.getPaymentId(), txHash));
        assertEquals("PAID", first.status());
        assertEquals(txHash, first.txHash());
        assertEquals(txHash.substring(2), order.getTransactionNo());
        assertEquals(txHash, quote.getTransactionHash());
        assertNotNull(quote.getConfirmedAt());
        verify(chain).verify(quote, txHash);
        verify(memberships).activateAt("owner", MembershipTier.STARTER, false, minedAt);

        var retry = service.settle("order1", "owner", new EthPaymentDtos.Settle(quote.getPaymentId(), txHash));
        assertEquals("PAID", retry.status());
        verify(memberships, times(1)).activateAt("owner", MembershipTier.STARTER, false, minedAt);
    }

    @Test
    void lateOrUnusableUpgradeGoesToReviewAfterVerifiedReceipt() {
        MembershipOrder order = order();
        order.setUpgrade(true);
        EthPaymentQuote quote = quote();
        String txHash = "0x" + "b".repeat(64);
        Instant minedAt = Instant.now().minusSeconds(30);
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        when(quotes.findById(quote.getPaymentId())).thenReturn(Optional.of(quote));
        when(quotes.findByIdForUpdate(quote.getPaymentId())).thenReturn(Optional.of(quote));
        when(chain.verify(quote, txHash)).thenReturn(minedAt);
        when(memberships.activateAt("owner", MembershipTier.STARTER, true, minedAt)).thenReturn(false);

        var result = service.settle("order1", "owner", new EthPaymentDtos.Settle(quote.getPaymentId(), txHash));
        assertEquals("REVIEW", result.status());
        assertEquals(txHash, result.txHash());
        assertNotNull(quote.getConfirmedAt());
    }

    private static MembershipOrder order() {
        MembershipOrder order = new MembershipOrder();
        order.setId("order1");
        order.setOwnerId("owner");
        order.setTier(MembershipTier.STARTER);
        order.setAmountVnd(49_000);
        order.setProvider(MembershipOrder.Provider.ETH);
        order.setStatus(MembershipOrder.Status.PENDING);
        order.setExpiresAt(Instant.now().plusSeconds(900));
        order.setCreatedAt(Instant.now());
        return order;
    }

    private static EthPaymentQuote quote() {
        EthPaymentQuote quote = new EthPaymentQuote();
        quote.setPaymentId("0x" + "1".repeat(64));
        quote.setOrderId("order1");
        quote.setAmountVnd(BigDecimal.valueOf(49_000));
        return quote;
    }
}
