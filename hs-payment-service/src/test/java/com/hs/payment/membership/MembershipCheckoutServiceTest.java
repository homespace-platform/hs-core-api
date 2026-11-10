package com.hs.payment.membership;

import com.hs.listing.membership.MembershipService;
import com.hs.listing.membership.MembershipTier;
import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MembershipCheckoutServiceTest {
    private final MembershipOrderRepository orders = mock(MembershipOrderRepository.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final VnpaySigner signer = new VnpaySigner("test-only-secret");
    private final MembershipCheckoutService service = new MembershipCheckoutService(orders, memberships, signer,
            new SepaySigner("test-sepay-secret"));

    @Test
    void sepayCheckoutCreatesSignedPostFormAndDisablesVnpay() {
        ReflectionTestUtils.setField(service, "sepayMerchantId", "MERCHANT_TEST");
        ReflectionTestUtils.setField(service, "sepayCheckoutUrl", "https://pay-sandbox.sepay.vn/v1/checkout/init");
        ReflectionTestUtils.setField(service, "sepayReturnUrl", "https://example.test/dashboard/membership/return");
        ReflectionTestUtils.setField(service, "sepayIpnSecret", "test-ipn-secret");
        when(memberships.quote("owner", MembershipTier.STARTER)).thenReturn(
                new MembershipService.Quote("STARTER", 49_000, "NEW", 10, null, null, false));

        assertThrows(AppException.class, () -> service.checkout("owner", MembershipTier.STARTER,
                MembershipOrder.Provider.VNPAY));
        var checkout = service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.SEPAY);
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
                () -> service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.SEPAY));
        assertEquals(ListingErrorCode.MEMBERSHIP_PROVIDER_NOT_CONFIGURED.getCode(), error.getCode());
        ReflectionTestUtils.setField(service, "sepayMerchantId", "MERCHANT_TEST");
        ReflectionTestUtils.setField(service, "sepayIpnSecret", "");
        assertEquals(error.getCode(), assertThrows(AppException.class,
                () -> service.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.SEPAY)).getCode());

        var withoutSigningSecret = new MembershipCheckoutService(orders, memberships, signer, new SepaySigner(""));
        ReflectionTestUtils.setField(withoutSigningSecret, "sepayMerchantId", "MERCHANT_TEST");
        ReflectionTestUtils.setField(withoutSigningSecret, "sepayCheckoutUrl", "https://pay-sandbox.sepay.vn/v1/checkout/init");
        ReflectionTestUtils.setField(withoutSigningSecret, "sepayReturnUrl", "https://example.test/dashboard/membership/return");
        ReflectionTestUtils.setField(withoutSigningSecret, "sepayIpnSecret", "ipn-secret");
        assertEquals(error.getCode(), assertThrows(AppException.class,
                () -> withoutSigningSecret.checkout("owner", MembershipTier.STARTER, MembershipOrder.Provider.SEPAY)).getCode());
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
        verify(memberships, never()).activate(anyString(), any(), anyBoolean());
    }

    @Test
    void sepayIpnActivatesOnceAndLatePaymentGoesToReview() throws Exception {
        MembershipOrder order = sepayOrder();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        when(memberships.activate("owner", MembershipTier.STARTER, false)).thenReturn(true);
        JsonNode callback = sepayCallback();
        assertTrue(service.sepayIpn(callback));
        assertEquals(MembershipOrder.Status.PAID, order.getStatus());
        assertTrue(service.sepayIpn(callback));
        verify(memberships, times(1)).activate("owner", MembershipTier.STARTER, false);

        assertTrue(service.sepayIpn(withTransaction(callback, "transaction_id", "tx456")));
        assertEquals(MembershipOrder.Status.REVIEW, order.getStatus());
        assertEquals("tx123", order.getTransactionNo());
        verify(memberships, times(1)).activate("owner", MembershipTier.STARTER, false);

        order.setStatus(MembershipOrder.Status.CANCELLED);
        assertTrue(service.sepayIpn(callback));
        assertEquals(MembershipOrder.Status.REVIEW, order.getStatus());
        verify(memberships, times(1)).activate("owner", MembershipTier.STARTER, false);
    }

    @Test
    void sepayPaymentAfterExpiryIsHeldForReviewEvenIfStatusStillPending() throws Exception {
        MembershipOrder order = sepayOrder();
        order.setExpiresAt(Instant.now().minusSeconds(1));
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));

        assertEquals("SEPAY", service.order("owner", "order1").provider());
        assertTrue(service.sepayIpn(sepayCallback()));
        assertEquals(MembershipOrder.Status.REVIEW, order.getStatus());
        verify(memberships, never()).activate(anyString(), any(), anyBoolean());
    }

    @Test
    void crossProviderIpnCannotActivateOrder() throws Exception {
        ReflectionTestUtils.setField(service, "tmnCode", "TESTCODE");
        MembershipOrder order = order();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        assertFalse(service.sepayIpn(sepayCallback()));
        order.setProvider(MembershipOrder.Provider.SEPAY);
        var callback = signed(Map.of("vnp_TmnCode", "TESTCODE", "vnp_TxnRef", "order1",
                "vnp_Amount", "4900000", "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00",
                "vnp_TransactionNo", "123"));
        assertEquals("01", service.ipn(callback).RspCode());
        verify(memberships, never()).activate(anyString(), any(), anyBoolean());
    }

    @Test
    void signedIpnActivatesOnceAndDuplicateCannotGrantAgain() {
        ReflectionTestUtils.setField(service, "tmnCode", "TESTCODE");
        MembershipOrder order = order();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        when(memberships.activate("owner", MembershipTier.STARTER, false)).thenReturn(true);
        Map<String, String> callback = signed(Map.of("vnp_TmnCode", "TESTCODE", "vnp_TxnRef", "order1",
                "vnp_Amount", "4900000", "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00",
                "vnp_TransactionNo", "123"));

        assertEquals("00", service.ipn(callback).RspCode());
        assertEquals(MembershipOrder.Status.PAID, order.getStatus());
        assertEquals("02", service.ipn(callback).RspCode());
        verify(memberships, times(1)).activate("owner", MembershipTier.STARTER, false);
    }

    @Test
    void amountMismatchAndFailedStatusNeverActivate() {
        ReflectionTestUtils.setField(service, "tmnCode", "TESTCODE");
        MembershipOrder order = order();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        var wrongAmount = signed(Map.of("vnp_TmnCode", "TESTCODE", "vnp_TxnRef", "order1",
                "vnp_Amount", "1", "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00"));
        assertEquals("04", service.ipn(wrongAmount).RspCode());
        var failed = signed(Map.of("vnp_TmnCode", "TESTCODE", "vnp_TxnRef", "order1",
                "vnp_Amount", "4900000", "vnp_ResponseCode", "00", "vnp_TransactionStatus", "01"));
        assertEquals("00", service.ipn(failed).RspCode());
        assertEquals(MembershipOrder.Status.FAILED, order.getStatus());
        verify(memberships, never()).activate(anyString(), any(), anyBoolean());
    }

    @Test
    void lateSuccessIsFlaggedForReconciliationWithoutActivation() {
        ReflectionTestUtils.setField(service, "tmnCode", "TESTCODE");
        MembershipOrder order = order(); order.setStatus(MembershipOrder.Status.EXPIRED);
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        var callback = signed(Map.of("vnp_TmnCode", "TESTCODE", "vnp_TxnRef", "order1",
                "vnp_Amount", "4900000", "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00",
                "vnp_TransactionNo", "123"));
        assertEquals("00", service.ipn(callback).RspCode());
        assertEquals(MembershipOrder.Status.REVIEW, order.getStatus());
        verify(memberships, never()).activate(anyString(), any(), anyBoolean());
    }

    @Test
    void cancelOnlyOwnPendingOrderAndKeepTerminalStatus() {
        MembershipOrder order = order();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));

        assertThrows(AppException.class, () -> service.cancel("other", "order1"));
        verify(memberships, never()).lockOwner(anyString());
        assertEquals("CANCELLED", service.cancel("owner", "order1").status());
        assertEquals("VNPAY", service.order("owner", "order1").provider());
        assertEquals("CANCELLED", service.cancel("owner", "order1").status());
        order.setStatus(MembershipOrder.Status.PAID);
        assertEquals("PAID", service.cancel("owner", "order1").status());
        verify(memberships, never()).activate(anyString(), any(), anyBoolean());
    }

    @Test
    void successAfterCancellationRequiresReviewWithoutGrantingUses() {
        ReflectionTestUtils.setField(service, "tmnCode", "TESTCODE");
        MembershipOrder order = order();
        when(orders.findById("order1")).thenReturn(Optional.of(order));
        when(orders.lockById("order1")).thenReturn(Optional.of(order));
        service.cancel("owner", "order1");
        var callback = signed(Map.of("vnp_TmnCode", "TESTCODE", "vnp_TxnRef", "order1",
                "vnp_Amount", "4900000", "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00",
                "vnp_TransactionNo", "123"));

        assertEquals("00", service.ipn(callback).RspCode());
        assertEquals(MembershipOrder.Status.REVIEW, order.getStatus());
        assertEquals("02", service.ipn(callback).RspCode());
        verify(memberships, never()).activate(anyString(), any(), anyBoolean());
    }

    private Map<String, String> signed(Map<String, String> fields) {
        var result = new java.util.HashMap<>(fields);
        result.put("vnp_SecureHash", signer.sign(result));
        return result;
    }

    private MembershipOrder order() {
        MembershipOrder o = new MembershipOrder();
        o.setId("order1"); o.setOwnerId("owner"); o.setTier(MembershipTier.STARTER);
        o.setAmountVnd(49_000); o.setStatus(MembershipOrder.Status.PENDING);
        o.setCreatedAt(Instant.now());
        return o;
    }

    private MembershipOrder sepayOrder() {
        MembershipOrder o = order();
        o.setProvider(MembershipOrder.Provider.SEPAY);
        o.setExpiresAt(Instant.now().plusSeconds(900));
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
