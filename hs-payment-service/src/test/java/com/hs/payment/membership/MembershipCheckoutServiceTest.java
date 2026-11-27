package com.hs.payment.membership;

import com.hs.listing.membership.MembershipKind;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.membership.MembershipTier;
import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.payment.model.EthPaymentQuote;
import com.hs.payment.advice.PaymentErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class MembershipCheckoutServiceTest {
    private final MembershipOrderRepository orders = mock(MembershipOrderRepository.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final com.hs.payment.repository.EthPaymentQuoteRepository ethQuotes = mock(com.hs.payment.repository.EthPaymentQuoteRepository.class);
    private final com.hs.payment.service.EthPaymentChainClient ethChain = mock(com.hs.payment.service.EthPaymentChainClient.class);
    private final com.hs.payment.membership.notification.MembershipNotificationWriter notifications =
            mock(com.hs.payment.membership.notification.MembershipNotificationWriter.class);
    private final com.hs.payment.receipt.MembershipReceiptService receipts = mock(com.hs.payment.receipt.MembershipReceiptService.class);
    private final MembershipOverpaymentRepository overpayments = mock(MembershipOverpaymentRepository.class);
    private final MembershipPaymentApplier applier = new MembershipPaymentApplier(orders, overpayments, memberships, notifications, receipts);
    private final MembershipCheckoutService service = new MembershipCheckoutService(orders, memberships,
            new SepaySigner("test-sepay-secret"), ethQuotes, ethChain, applier);

    @Test
    void ethCheckoutNeedsNoSepayConfigurationAndKeepsOrderPending() {
        when(memberships.quote("owner", MembershipTier.STARTER)).thenReturn(
                new MembershipService.Quote("STARTER", 49_000, "NEW", 10, null, null, false));
        var checkout = service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.ETH, false);
        assertEquals("ETH", checkout.provider());
        assertEquals(49_000, checkout.amountVnd());
        assertTrue(checkout.formFields().isEmpty());
        verify(orders).save(argThat(o -> o.getProvider() == MembershipOrder.Provider.ETH
                && o.getStatus() == MembershipOrder.Status.PENDING));
    }

    private MembershipOrder paidAgo(MembershipTier tier, Integer quantity, long minutes) {
        MembershipOrder o = new MembershipOrder();
        o.setTier(tier);
        o.setKind(quantity == null ? MembershipKind.PLAN : MembershipKind.ADDON);
        o.setQuantity(quantity);
        o.setStatus(MembershipOrder.Status.PAID);
        o.setPaidAt(Instant.now().minusSeconds(minutes * 60));
        return o;
    }

    @Test
    void justPaidSameItemNeedsExplicitConfirmation() {
        when(memberships.quote("owner", MembershipTier.STARTER)).thenReturn(
                new MembershipService.Quote("STARTER", 49_000, "NEW", 10, null, null, false));
        when(orders.findByOwnerIdAndStatusAndPaidAtAfter(eq("owner"), eq(MembershipOrder.Status.PAID), any()))
                .thenReturn(java.util.List.of(paidAgo(MembershipTier.STARTER, null, 2)));
        AppException e = assertThrows(AppException.class,
                () -> service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.ETH, false));
        assertEquals(ListingErrorCode.MEMBERSHIP_RECENTLY_PAID.getCode(), e.getCode());
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getErrorMessage().startsWith("Bạn vừa thanh toán gói này lúc "));
        verify(orders, never()).save(any());
        service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.ETH, true);
        verify(orders).save(any());
    }

    @Test
    void repeatGuardIgnoresDifferentItemAndOldPayments() {
        // The 10 minute cutoff is applied by the repository query, so an old payment is simply not returned.
        when(memberships.quote("owner", MembershipTier.STARTER)).thenReturn(
                new MembershipService.Quote("STARTER", 49_000, "NEW", 10, null, null, false));
        when(memberships.addonAmountVnd(3)).thenReturn(24_000);
        when(orders.findByOwnerIdAndStatusAndPaidAtAfter(eq("owner"), eq(MembershipOrder.Status.PAID), any()))
                .thenReturn(java.util.List.of(paidAgo(MembershipTier.PRO, null, 1), paidAgo(null, 2, 1)));
        service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.ETH, false);
        service.addonCheckout("owner", 3, MembershipOrder.Provider.ETH, false);
        verify(orders, times(2)).save(any());
        assertThrows(AppException.class, () -> service.addonCheckout("owner", 2, MembershipOrder.Provider.ETH, false));
    }

    @Test
    void signedEthQuoteBlocksCancellationUntilFinalizedUnpaid() {
        MembershipOrder order = new MembershipOrder();
        order.setId("order1");
        order.setOwnerId("owner");
        order.setTier(MembershipTier.STARTER);
        order.setProvider(MembershipOrder.Provider.ETH);
        order.setStatus(MembershipOrder.Status.PENDING);
        EthPaymentQuote quote = new EthPaymentQuote();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        when(ethQuotes.findFirstByOrderIdOrderByExpiresAtDesc("order1")).thenReturn(Optional.of(quote));
        doThrow(new AppException(PaymentErrorCode.ETH_PAYMENT_REVIEW_REQUIRED))
                .when(ethChain).requireFinalizedUnpaid(quote);
        assertThrows(AppException.class, () -> service.cancel("owner", "order1"));
        assertEquals(MembershipOrder.Status.PENDING, order.getStatus());
        doNothing().when(ethChain).requireFinalizedUnpaid(quote);
        assertEquals("CANCELLED", service.cancel("owner", "order1").status());
    }

    @Test
    void sepayCheckoutCreatesSignedPostFormAndRejectsVnpay() {
        ReflectionTestUtils.setField(service, "sepayMerchantId", "MERCHANT_TEST");
        ReflectionTestUtils.setField(service, "sepayCheckoutUrl", "https://pay-sandbox.sepay.vn/v1/checkout/init");
        ReflectionTestUtils.setField(service, "sepayReturnUrl", "https://example.test/dashboard/membership/return");
        ReflectionTestUtils.setField(service, "sepayIpnSecret", "test-ipn-secret");
        when(memberships.quote("owner", MembershipTier.STARTER)).thenReturn(
                new MembershipService.Quote("STARTER", 49_000, "NEW", 10, null, null, false));

        assertThrows(AppException.class, () -> service.checkout("owner", MembershipTier.STARTER,
                MembershipOrder.Provider.VNPAY, false));
        var checkout = service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.SEPAY, false);
        assertEquals("SEPAY", checkout.provider());
        assertEquals(49_000, checkout.amountVnd());
        assertEquals("https://pay-sandbox.sepay.vn/v1/checkout/init", checkout.paymentUrl());
        assertEquals("PURCHASE", checkout.formFields().get("operation"));
        assertEquals(checkout.orderId(), checkout.formFields().get("order_invoice_number"));
        assertTrue(checkout.formFields().get("success_url").contains("orderId=" + checkout.orderId()));
        assertEquals(new SepaySigner("test-sepay-secret").sign(checkout.formFields()),
                checkout.formFields().get("signature"));
        verify(orders, times(1)).save(argThat(o -> o.getProvider() == MembershipOrder.Provider.SEPAY));
    }

    @Test
    void sepayCheckoutRejectsIncompleteConfigurationBeforeCreatingOrder() {
        ReflectionTestUtils.setField(service, "sepayMerchantId", "MERCHANT_TEST");
        ReflectionTestUtils.setField(service, "sepayCheckoutUrl", "https://pay-sandbox.sepay.vn/v1/checkout/init");
        ReflectionTestUtils.setField(service, "sepayReturnUrl", "https://example.test/dashboard/membership/return");
        ReflectionTestUtils.setField(service, "sepayIpnSecret", "ipn-secret");

        ReflectionTestUtils.setField(service, "sepayMerchantId", "");
        AppException error = assertThrows(AppException.class,
                () -> service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.SEPAY, false));
        assertEquals(ListingErrorCode.MEMBERSHIP_PROVIDER_NOT_CONFIGURED.getCode(), error.getCode());
        ReflectionTestUtils.setField(service, "sepayMerchantId", "MERCHANT_TEST");
        ReflectionTestUtils.setField(service, "sepayIpnSecret", "");
        assertEquals(error.getCode(), assertThrows(AppException.class,
                () -> service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.SEPAY, false)).getCode());

        var withoutSigningSecret = new MembershipCheckoutService(orders, memberships, new SepaySigner(""), ethQuotes, ethChain,
                applier);
        ReflectionTestUtils.setField(withoutSigningSecret, "sepayMerchantId", "MERCHANT_TEST");
        ReflectionTestUtils.setField(withoutSigningSecret, "sepayCheckoutUrl", "https://pay-sandbox.sepay.vn/v1/checkout/init");
        ReflectionTestUtils.setField(withoutSigningSecret, "sepayReturnUrl", "https://example.test/dashboard/membership/return");
        ReflectionTestUtils.setField(withoutSigningSecret, "sepayIpnSecret", "ipn-secret");
        assertEquals(error.getCode(), assertThrows(AppException.class,
                () -> withoutSigningSecret.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.SEPAY, false)).getCode());
        verifyNoInteractions(orders, memberships);
    }

    @Test
    void sepayIpnRequiresSecretAndMatchingApprovedPayment() throws Exception {
        ReflectionTestUtils.setField(service, "sepayIpnSecret", "test-ipn-secret");
        assertFalse(service.sepayAuthenticated(null));
        assertFalse(service.sepayAuthenticated("wrong"));
        assertTrue(service.sepayAuthenticated("test-ipn-secret"));
        MembershipOrder order = sepayOrder();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        JsonNode callback = sepayCallback();
        assertFalse(service.sepayIpn(((com.fasterxml.jackson.databind.node.ObjectNode) callback.deepCopy())
                .put("notification_type", "TRANSACTION_VOID")));
        assertFalse(service.sepayIpn(withOrder(callback, "order_amount", "49001")));
        assertFalse(service.sepayIpn(withOrder(callback, "order_currency", "USD")));
        assertFalse(service.sepayIpn(withTransaction(callback, "transaction_amount", "1")));
        assertFalse(service.sepayIpn(withTransaction(callback, "transaction_status", "DECLINED")));
        assertEquals(MembershipOrder.Status.PENDING, order.getStatus());
        verify(memberships, never()).activate(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void sepayIpnActivatesOnceAndSecondTransactionIsOverpayment() throws Exception {
        MembershipOrder order = sepayOrder();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        when(memberships.activate("owner", MembershipTier.STARTER, false, "order1")).thenReturn(true);
        JsonNode callback = sepayCallback();
        assertTrue(service.sepayIpn(callback));
        assertEquals(MembershipOrder.Status.PAID, order.getStatus());
        verify(receipts, times(1)).capture(order);
        assertTrue(service.sepayIpn(callback));
        verify(receipts, times(1)).capture(order); // replayed IPN returns early
        verify(memberships, times(1)).activate("owner", MembershipTier.STARTER, false, "order1");

        // A second distinct transaction is an overpayment to refund: no extra activation, no new order; replay adds nothing.
        assertTrue(service.sepayIpn(withTransaction(callback, "transaction_id", "tx456")));
        var over = org.mockito.ArgumentCaptor.forClass(MembershipOverpayment.class);
        verify(overpayments).save(over.capture());
        assertEquals("tx456", over.getValue().getTransactionNo());
        assertEquals("order1", over.getValue().getOrderId());
        assertEquals(49_000, over.getValue().getAmountVnd());
        verify(receipts).captureOverpayment(over.getValue());
        verify(notifications).enqueueOverpayment(over.getValue());
        when(overpayments.existsByProviderAndTransactionNo(MembershipOrder.Provider.SEPAY, "tx456")).thenReturn(true);
        assertTrue(service.sepayIpn(withTransaction(callback, "transaction_id", "tx456")));
        verify(overpayments, times(1)).save(any());
        assertEquals(MembershipOrder.Status.PAID, order.getStatus());
        assertEquals("tx123", order.getTransactionNo());
        verify(memberships, times(1)).activate(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void sepayPaymentAfterExpiryOrCancelActivatesAsLatePayment() throws Exception {
        MembershipOrder order = sepayOrder();
        order.setExpiresAt(Instant.now().minusSeconds(1));
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));

        assertEquals("SEPAY", service.order("owner", "order1").provider());
        when(memberships.activate("owner", MembershipTier.STARTER, false, "order1")).thenReturn(true);
        assertTrue(service.sepayIpn(sepayCallback()));
        assertEquals(MembershipOrder.Status.PAID, order.getStatus());
        verify(receipts).capture(order);
    }

    @Test
    void sepayIpnIgnoresLegacyVnpayOrders() throws Exception {
        MembershipOrder order = legacyVnpayOrder();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        assertFalse(service.sepayIpn(sepayCallback()));
        verify(memberships, never()).activate(anyString(), any(), anyBoolean(), anyString());
        assertEquals(MembershipOrder.Status.PENDING, order.getStatus());
    }

    @Test
    void cancelOnlyOwnPendingOrderAndKeepTerminalStatus() {
        MembershipOrder order = legacyVnpayOrder();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));

        assertThrows(AppException.class, () -> service.cancel("other", "order1"));
        verify(memberships, never()).lockOwner(anyString());
        assertEquals("CANCELLED", service.cancel("owner", "order1").status());
        assertEquals("VNPAY", service.order("owner", "order1").provider());
        assertEquals("CANCELLED", service.cancel("owner", "order1").status());
        order.setStatus(MembershipOrder.Status.PAID);
        assertEquals("PAID", service.cancel("owner", "order1").status());
        verify(memberships, never()).activate(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void nullProviderSurfacesAsVnpayWithoutMutatingStorage() {
        MembershipOrder order = legacyVnpayOrder();
        order.setProvider(null);
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        assertEquals("VNPAY", service.order("owner", "order1").provider());
        assertNull(order.getProvider());
    }

    @Test
    void addonCheckoutPricesOnTheServerRejectsBadInputAndSharesThePendingRule() {
        ReflectionTestUtils.setField(service, "sepayMerchantId", "MERCHANT_TEST");
        ReflectionTestUtils.setField(service, "sepayCheckoutUrl", "https://pay-sandbox.sepay.vn/v1/checkout/init");
        ReflectionTestUtils.setField(service, "sepayReturnUrl", "https://example.test/dashboard/membership/return");
        ReflectionTestUtils.setField(service, "sepayIpnSecret", "test-ipn-secret");
        when(memberships.addonAmountVnd(3)).thenReturn(24_000);
        assertEquals(ListingErrorCode.MEMBERSHIP_PROVIDER_UNAVAILABLE.getCode(), assertThrows(AppException.class,
                () -> service.addonCheckout("owner", 3, MembershipOrder.Provider.VNPAY, false)).getCode());
        when(memberships.addonAmountVnd(11))
                .thenThrow(new AppException(ListingErrorCode.MEMBERSHIP_ADDON_QUANTITY_INVALID));
        assertThrows(AppException.class, () -> service.addonCheckout("owner", 11, MembershipOrder.Provider.SEPAY, false));
        verify(orders, never()).save(any());

        var checkout = service.addonCheckout("owner", 3, MembershipOrder.Provider.SEPAY, false);
        assertEquals(24_000, checkout.amountVnd());
        assertEquals("SEPAY", checkout.provider());
        assertEquals("24000", checkout.formFields().get("order_amount"));
        var saved = org.mockito.ArgumentCaptor.forClass(MembershipOrder.class);
        verify(orders).save(saved.capture());
        assertEquals(MembershipKind.ADDON, saved.getValue().getKind());
        assertEquals(3, saved.getValue().getQuantity());
        assertNull(saved.getValue().getTier());
        var view = MembershipCheckoutService.OrderView.from(saved.getValue());
        assertEquals("ADDON", view.kind());
        assertEquals(3, view.quantity());
        assertNull(view.tier());
        verify(memberships).lockOwner("owner");
        var ethCheckout = service.addonCheckout("owner", 3, MembershipOrder.Provider.ETH, false);
        assertEquals("ETH", ethCheckout.provider());
        assertEquals(24_000, ethCheckout.amountVnd());
        assertTrue(ethCheckout.formFields().isEmpty());

        // Spam: the same item and provider returns the existing pending order; no new row.
        when(orders.findByOwnerIdAndStatus("owner", MembershipOrder.Status.PENDING))
                .thenReturn(java.util.List.of(saved.getValue()));
        var again = service.addonCheckout("owner", 3, MembershipOrder.Provider.SEPAY, false);
        assertEquals(saved.getValue().getId(), again.orderId());
        verify(orders, times(2)).save(any()); // only the two orders created above

        // A different item cancels the old pending order and creates a new one.
        when(memberships.quote("owner", MembershipTier.STARTER)).thenReturn(
                new MembershipService.Quote("STARTER", 49_000, "NEW", 10, null, null, false));
        var plan = service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.SEPAY, false);
        assertNotEquals(saved.getValue().getId(), plan.orderId());
        assertEquals(MembershipOrder.Status.CANCELLED, saved.getValue().getStatus());
    }

    @Test
    void addonIpnRejectsAmountMismatchAndActivatesOnceForDuplicates() throws Exception {
        MembershipOrder order = sepayOrder();
        order.setKind(MembershipKind.ADDON);
        order.setTier(null);
        order.setQuantity(2);
        order.setAmountVnd(16_000);
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        when(memberships.activateAddon("owner", 2, "order1")).thenReturn(true);
        JsonNode callback = withTransaction(withOrder(sepayCallback(), "order_amount", "16000"),
                "transaction_amount", "16000");
        assertFalse(service.sepayIpn(withOrder(callback, "order_amount", "8000")));
        assertFalse(service.sepayIpn(withTransaction(callback, "transaction_amount", "8000")));
        verify(memberships, never()).activateAddon(anyString(), anyInt(), anyString());
        assertEquals(MembershipOrder.Status.PENDING, order.getStatus());

        assertTrue(service.sepayIpn(callback));
        assertTrue(service.sepayIpn(callback));
        assertEquals(MembershipOrder.Status.PAID, order.getStatus());
        verify(memberships, times(1)).activateAddon("owner", 2, "order1");
        assertTrue(service.sepayIpn(withTransaction(callback, "transaction_id", "tx456")));
        assertEquals(MembershipOrder.Status.PAID, order.getStatus());
        verify(overpayments, times(1)).save(any());
        verify(memberships, times(1)).activateAddon("owner", 2, "order1");
        verify(memberships, never()).activate(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void paidSepayOrderWhoseActivationIsRefusedBecomesRefundableOverpayment() throws Exception {
        MembershipOrder order = sepayOrder();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        assertTrue(service.sepayIpn(sepayCallback())); // activate() returns false.
        assertEquals(MembershipOrder.Status.FAILED, order.getStatus());
        verify(overpayments).save(any());
        verify(receipts, never()).capture(any());
    }

    private MembershipOrder legacyVnpayOrder() {
        MembershipOrder o = new MembershipOrder();
        o.setId("order1"); o.setOwnerId("owner"); o.setTier(MembershipTier.STARTER);
        o.setAmountVnd(49_000); o.setStatus(MembershipOrder.Status.PENDING);
        o.setProvider(MembershipOrder.Provider.VNPAY);
        o.setCreatedAt(Instant.now());
        o.setExpiresAt(Instant.now().plusSeconds(900));
        return o;
    }

    private MembershipOrder sepayOrder() {
        MembershipOrder o = legacyVnpayOrder();
        o.setProvider(MembershipOrder.Provider.SEPAY);
        return o;
    }

    private JsonNode sepayCallback() throws Exception {
        return new ObjectMapper().readTree("""
                {"notification_type":"ORDER_PAID",
                 "order":{"order_invoice_number":"order1","order_status":"CAPTURED",
                          "order_currency":"VND","order_amount":"49000.00"},
                 "transaction":{"transaction_id":"tx123","transaction_type":"PAYMENT",
                                "transaction_status":"APPROVED","transaction_amount":"49000",
                                "transaction_currency":"VND"}}
                """);
    }

    private JsonNode withOrder(JsonNode callback, String name, String value) {
        JsonNode copy = callback.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.path("order")).put(name, value);
        return copy;
    }

    private JsonNode withTransaction(JsonNode callback, String name, String value) {
        JsonNode copy = callback.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.path("transaction")).put(name, value);
        return copy;
    }
}
