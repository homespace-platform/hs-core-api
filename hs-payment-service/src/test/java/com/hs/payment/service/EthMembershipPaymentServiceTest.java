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
    private final com.hs.payment.membership.notification.MembershipNotificationWriter notifications =
            mock(com.hs.payment.membership.notification.MembershipNotificationWriter.class);
    private final EthPaymentService service = new EthPaymentService(orders, memberships, challenges, quotes,
            prices, chain, signatures, notifications);

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
        when(memberships.activate("owner", MembershipTier.STARTER, false, "order1")).thenReturn(true);

        var first = service.settle("order1", "owner", new EthPaymentDtos.Settle(quote.getPaymentId(), txHash));
        assertEquals("PAID", first.status());
        assertEquals(txHash, first.txHash());
        assertEquals(txHash.substring(2), order.getTransactionNo());
        assertEquals(txHash, quote.getTransactionHash());
        assertNotNull(quote.getConfirmedAt());
        verify(chain).verify(quote, txHash);
        verify(memberships).activate("owner", MembershipTier.STARTER, false, "order1");

        var retry = service.settle("order1", "owner", new EthPaymentDtos.Settle(quote.getPaymentId(), txHash));
        assertEquals("PAID", retry.status());
        verify(memberships, times(1)).activate("owner", MembershipTier.STARTER, false, "order1");
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
        when(memberships.activate("owner", MembershipTier.STARTER, true, "order1")).thenReturn(false);

        var result = service.settle("order1", "owner", new EthPaymentDtos.Settle(quote.getPaymentId(), txHash));
        assertEquals("REVIEW", result.status());
        assertEquals(txHash, result.txHash());
        assertNotNull(quote.getConfirmedAt());
    }

    @Test
    void ethAddonRejectsAmountMismatchThenActivatesOnceForDuplicateSettlement() {
        MembershipOrder order = order();
        order.setKind(com.hs.listing.membership.MembershipKind.ADDON);
        order.setTier(null);
        order.setQuantity(3);
        order.setAmountVnd(24_000);
        EthPaymentQuote quote = quote(); // Still priced at 49,000 VND.
        String txHash = "0x" + "c".repeat(64);
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        when(quotes.findById(quote.getPaymentId())).thenReturn(Optional.of(quote));
        when(quotes.findByIdForUpdate(quote.getPaymentId())).thenReturn(Optional.of(quote));
        when(chain.verify(quote, txHash)).thenReturn(Instant.now().minusSeconds(30));
        when(memberships.activateAddon("owner", 3, "order1")).thenReturn(true);
        var settle = new EthPaymentDtos.Settle(quote.getPaymentId(), txHash);

        assertThrows(com.hs.common.advice.entity.AppException.class, () -> service.settle("order1", "owner", settle));
        assertEquals(MembershipOrder.Status.PENDING, order.getStatus());
        verify(memberships, never()).activateAddon(any(), org.mockito.ArgumentMatchers.anyInt(), any());

        quote.setAmountVnd(BigDecimal.valueOf(24_000));
        var paid = service.settle("order1", "owner", settle);
        assertEquals("PAID", paid.status());
        assertEquals("ADDON", paid.kind());
        assertNull(paid.tier());
        assertEquals("PAID", service.settle("order1", "owner", settle).status());
        verify(memberships, times(1)).activateAddon("owner", 3, "order1");
        verify(memberships, never()).activate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
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
