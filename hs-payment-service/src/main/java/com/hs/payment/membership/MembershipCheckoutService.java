package com.hs.payment.membership;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.membership.MembershipKind;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.membership.MembershipTier;
import com.hs.payment.repository.EthPaymentQuoteRepository;
import com.hs.payment.service.EthPaymentChainClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MembershipCheckoutService {
    private static final Logger log = LoggerFactory.getLogger(MembershipCheckoutService.class);
    private final MembershipOrderRepository orders;
    private final MembershipService memberships;
    private final SepaySigner sepaySigner;
    private final EthPaymentQuoteRepository ethQuotes;
    private final EthPaymentChainClient ethChain;
    private final MembershipPaymentApplier applier;
    @PersistenceContext private EntityManager em;
    @Value("${homespace.membership.sepay.merchant-id}") private String sepayMerchantId;
    @Value("${homespace.membership.sepay.checkout-url}") private String sepayCheckoutUrl;
    @Value("${homespace.membership.sepay.return-url}") private String sepayReturnUrl;
    @Value("${homespace.membership.sepay.ipn-secret}") private String sepayIpnSecret;

    public record Checkout(String orderId, String paymentUrl, int amountVnd, String provider, Map<String, String> formFields) {}
    public record OrderView(String id, String tier, int amountVnd, String status, Instant createdAt, Instant paidAt,
                            String provider, String txHash, String kind, Integer quantity) {
        public static OrderView from(MembershipOrder o) {
            // Legacy rows may store null provider from the pre-SePay era; surface as VNPAY without rewriting storage.
            return new OrderView(o.getId(), o.getTier() == null ? null : o.getTier().name(), o.getAmountVnd(),
                    o.getStatus().name(),
                    o.getCreatedAt(), o.getPaidAt(),
                    o.getProvider() == null ? MembershipOrder.Provider.VNPAY.name() : o.getProvider().name(),
                    o.getProvider() == MembershipOrder.Provider.ETH && o.getTransactionNo() != null
                            ? "0x" + o.getTransactionNo() : null,
                    o.getKind().name(), o.getQuantity());
        }
    }

    @Transactional
    public Checkout checkout(String ownerId, MembershipTier tier, MembershipOrder.Provider provider,
                             boolean confirmRepeat) {
        requireProvider(provider);
        MembershipService.Quote quote = memberships.quote(ownerId, tier); // Locks owner for concurrent orders.
        MembershipOrder order = new MembershipOrder();
        order.setTier(tier);
        order.setAmountVnd(quote.amountVnd());
        order.setUpgrade(quote.upgrade());
        // The ETH quote expiry is capped by this value, so it is stored on the gateway (real) clock.
        order.setUpgradeDeadline(quote.upgrade() ? memberships.realTimeOf(quote.upgradeDeadline()) : null);
        return place(order, ownerId, provider, "HomeSpace membership " + tier.name(), confirmRepeat);
    }

    /** Same providers and order rules as plans. The amount is computed here from the validated quantity. */
    @Transactional
    public Checkout addonCheckout(String ownerId, int quantity, MembershipOrder.Provider provider,
                                  boolean confirmRepeat) {
        requireProvider(provider);
        int amountVnd = memberships.addonAmountVnd(quantity);
        memberships.lockOwner(ownerId);
        MembershipOrder order = new MembershipOrder();
        order.setKind(MembershipKind.ADDON);
        order.setQuantity(quantity);
        order.setAmountVnd(amountVnd);
        return place(order, ownerId, provider, "HomeSpace extra listing uses x" + quantity, confirmRepeat);
    }

    private void requireProvider(MembershipOrder.Provider provider) {
        if (provider != MembershipOrder.Provider.SEPAY && provider != MembershipOrder.Provider.ETH)
            throw new AppException(ListingErrorCode.MEMBERSHIP_PROVIDER_UNAVAILABLE);
        if (provider == MembershipOrder.Provider.SEPAY && (isBlank(sepayMerchantId) || isBlank(sepayReturnUrl)
                || isBlank(sepayCheckoutUrl) || isBlank(sepayIpnSecret) || !sepaySigner.isConfigured()))
            throw new AppException(ListingErrorCode.MEMBERSHIP_PROVIDER_NOT_CONFIGURED);
    }

    /**
     * Caller holds the owner lock. At most one live PENDING order per owner: the same item and provider returns the
     * existing order (spam, double click, several tabs); a different item cancels the old order first.
     */
    private Checkout place(MembershipOrder order, String ownerId, MembershipOrder.Provider provider,
                           String description, boolean confirmRepeat) {
        Instant now = Instant.now();
        if (!confirmRepeat) rejectRecentRepeat(order, ownerId, now);
        for (MembershipOrder previous : orders.findByOwnerIdAndStatus(ownerId, MembershipOrder.Status.PENDING)) {
            requireEthQuoteCleared(previous);
            if (previous.getExpiresAt().isAfter(now) && sameItem(previous, order, provider)) return checkoutOf(previous, description);
            previous.setStatus(previous.getExpiresAt().isAfter(now)
                    ? MembershipOrder.Status.CANCELLED : MembershipOrder.Status.EXPIRED);
        }
        orders.flush(); // free the one-pending-per-owner unique index before inserting
        order.setId(UUID.randomUUID().toString().replace("-", ""));
        order.setOwnerId(ownerId);
        order.setStatus(MembershipOrder.Status.PENDING);
        order.setProvider(provider);
        order.setCreatedAt(now);
        order.setExpiresAt(now.plus(15, java.time.temporal.ChronoUnit.MINUTES));
        orders.save(order);
        return checkoutOf(order, description);
    }

    /** A just-PAID identical item is most likely a retry after a payment the user thought failed, so make them confirm. */
    private static final java.time.Duration REPEAT_WINDOW = java.time.Duration.ofMinutes(10);

    private void rejectRecentRepeat(MembershipOrder order, String ownerId, Instant now) {
        for (MembershipOrder paid : orders.findByOwnerIdAndStatusAndPaidAtAfter(ownerId, MembershipOrder.Status.PAID,
                now.minus(REPEAT_WINDOW))) {
            if (paid.getKind() == order.getKind() && paid.getTier() == order.getTier()
                    && java.util.Objects.equals(paid.getQuantity(), order.getQuantity()))
                throw new AppException(ListingErrorCode.MEMBERSHIP_RECENTLY_PAID,
                        "Bạn vừa thanh toán gói này lúc " + paid.getPaidAt().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                                + ". Nếu vẫn muốn mua thêm, hãy xác nhận lại.");
        }
    }

    private static boolean sameItem(MembershipOrder a, MembershipOrder b, MembershipOrder.Provider provider) {
        return a.getProvider() == provider && a.getKind() == b.getKind() && a.getTier() == b.getTier()
                && java.util.Objects.equals(a.getQuantity(), b.getQuantity()) && a.isUpgrade() == b.isUpgrade()
                && a.getAmountVnd() == b.getAmountVnd();
    }

    private Checkout checkoutOf(MembershipOrder order, String description) {
        MembershipOrder.Provider provider = order.getProvider();
        if (provider == MembershipOrder.Provider.ETH)
            return new Checkout(order.getId(), "", order.getAmountVnd(), provider.name(), Map.of());
        String callback = sepayReturnUrl + (sepayReturnUrl.contains("?") ? "&" : "?") + "orderId=" + order.getId();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("order_amount", String.valueOf(order.getAmountVnd()));
        fields.put("merchant", sepayMerchantId);
        fields.put("currency", "VND");
        fields.put("operation", "PURCHASE");
        fields.put("order_description", description);
        fields.put("order_invoice_number", order.getId());
        fields.put("success_url", callback + "&sepayReturn=success");
        fields.put("error_url", callback + "&sepayReturn=error");
        fields.put("cancel_url", callback + "&sepayReturn=cancel");
        fields.put("signature", sepaySigner.sign(fields));
        return new Checkout(order.getId(), sepayCheckoutUrl, order.getAmountVnd(), "SEPAY", fields);
    }

    @Transactional(readOnly = true)
    public OrderView order(String ownerId, String id) {
        MembershipOrder o = orders.findById(id).orElseThrow(() -> new AppException(ListingErrorCode.MEMBERSHIP_ORDER_NOT_FOUND));
        if (!o.getOwnerId().equals(ownerId)) throw new AppException(ListingErrorCode.LISTING_FORBIDDEN);
        return OrderView.from(o);
    }

    @Transactional
    public OrderView cancel(String ownerId, String id) {
        MembershipOrder preview = orders.findById(id)
                .orElseThrow(() -> new AppException(ListingErrorCode.MEMBERSHIP_ORDER_NOT_FOUND));
        if (!preview.getOwnerId().equals(ownerId)) throw new AppException(ListingErrorCode.LISTING_FORBIDDEN);
        memberships.lockOwner(ownerId);
        MembershipOrder o = orders.lockById(id)
                .orElseThrow(() -> new AppException(ListingErrorCode.MEMBERSHIP_ORDER_NOT_FOUND));
        refresh(o);
        if (!o.getOwnerId().equals(ownerId)) throw new AppException(ListingErrorCode.LISTING_FORBIDDEN);
        if (o.getStatus() == MembershipOrder.Status.PENDING) {
            requireEthQuoteCleared(o);
            o.setStatus(MembershipOrder.Status.CANCELLED);
        }
        return OrderView.from(o);
    }

    /**
     * The unlocked preview read leaves the order in the first-level cache, so lockById returns that stale instance.
     * Reload it under the lock so a concurrent winner's PAID/CANCELLED state is seen. Null in direct unit fixtures.
     */
    private void refresh(Object entity) {
        if (em != null) em.refresh(entity);
    }

    private void requireEthQuoteCleared(MembershipOrder order) {
        if (order.getProvider() != MembershipOrder.Provider.ETH) return;
        ethQuotes.findFirstByOrderIdOrderByExpiresAtDesc(order.getId())
                .ifPresent(ethChain::requireFinalizedUnpaid);
    }

    public boolean sepayAuthenticated(String suppliedSecret) {
        return !isBlank(sepayIpnSecret) && suppliedSecret != null
                && MessageDigest.isEqual(sepayIpnSecret.getBytes(StandardCharsets.UTF_8),
                suppliedSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public boolean sepayIpn(JsonNode body) {
        if (body == null || !"ORDER_PAID".equals(body.path("notification_type").asText())) return false;
        JsonNode paymentOrder = body.path("order");
        JsonNode transaction = body.path("transaction");
        String id = paymentOrder.path("order_invoice_number").asText();
        String transactionId = transaction.path("transaction_id").asText();
        if (id.isBlank() || transactionId.isBlank() || transactionId.length() > 64
                || !"CAPTURED".equals(paymentOrder.path("order_status").asText())
                || !"APPROVED".equals(transaction.path("transaction_status").asText())
                || !"PAYMENT".equals(transaction.path("transaction_type").asText())
                || !"VND".equals(paymentOrder.path("order_currency").asText())
                || !"VND".equals(transaction.path("transaction_currency").asText())) return false;

        BigDecimal orderAmount;
        BigDecimal transactionAmount;
        try {
            orderAmount = new BigDecimal(paymentOrder.path("order_amount").asText());
            transactionAmount = new BigDecimal(transaction.path("transaction_amount").asText());
        } catch (NumberFormatException e) {
            return false;
        }
        MembershipOrder preview = orders.findById(id).orElse(null);
        if (preview == null || preview.getProvider() != MembershipOrder.Provider.SEPAY) return false;
        memberships.lockOwner(preview.getOwnerId());
        MembershipOrder o = orders.lockById(id).orElse(null);
        if (o != null) refresh(o);
        if (o == null || o.getProvider() != MembershipOrder.Provider.SEPAY
                || orderAmount.compareTo(BigDecimal.valueOf(o.getAmountVnd())) != 0
                || transactionAmount.compareTo(BigDecimal.valueOf(o.getAmountVnd())) != 0) return false;
        applier.apply(o, transactionId); // late, repeated or unactivatable money is applied, never parked
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
