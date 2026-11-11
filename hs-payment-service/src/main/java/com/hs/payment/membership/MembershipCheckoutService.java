package com.hs.payment.membership;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.membership.MembershipTier;
import com.hs.payment.repository.EthPaymentQuoteRepository;
import com.hs.payment.service.EthPaymentChainClient;
import com.fasterxml.jackson.databind.JsonNode;
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
    @Value("${homespace.membership.sepay.merchant-id}") private String sepayMerchantId;
    @Value("${homespace.membership.sepay.checkout-url}") private String sepayCheckoutUrl;
    @Value("${homespace.membership.sepay.return-url}") private String sepayReturnUrl;
    @Value("${homespace.membership.sepay.ipn-secret}") private String sepayIpnSecret;

    public record Checkout(String orderId, String paymentUrl, int amountVnd, String provider, Map<String, String> formFields) {}
    public record OrderView(String id, String tier, int amountVnd, String status, Instant createdAt, Instant paidAt,
                            String provider, String txHash) {
        public static OrderView from(MembershipOrder o) {
            // Legacy rows may store null provider from the pre-SePay era; surface as VNPAY without rewriting storage.
            return new OrderView(o.getId(), o.getTier().name(), o.getAmountVnd(), o.getStatus().name(),
                    o.getCreatedAt(), o.getPaidAt(),
                    o.getProvider() == null ? MembershipOrder.Provider.VNPAY.name() : o.getProvider().name(),
                    o.getProvider() == MembershipOrder.Provider.ETH && o.getTransactionNo() != null
                            ? "0x" + o.getTransactionNo() : null);
        }
    }

    @Transactional
    public Checkout checkout(String ownerId, MembershipTier tier, MembershipOrder.Provider provider) {
        if (provider != MembershipOrder.Provider.SEPAY && provider != MembershipOrder.Provider.ETH)
            throw new AppException(ListingErrorCode.MEMBERSHIP_PROVIDER_UNAVAILABLE);
        if (provider == MembershipOrder.Provider.SEPAY && (isBlank(sepayMerchantId) || isBlank(sepayReturnUrl) || isBlank(sepayCheckoutUrl)
                || isBlank(sepayIpnSecret) || !sepaySigner.isConfigured()))
            throw new AppException(ListingErrorCode.MEMBERSHIP_PROVIDER_NOT_CONFIGURED);
        MembershipService.Quote quote = memberships.quote(ownerId, tier); // Locks owner for concurrent orders.
        Instant now = Instant.now();
        boolean pending = false;
        for (MembershipOrder previous : orders.findByOwnerIdAndStatus(ownerId, MembershipOrder.Status.PENDING)) {
            if (!previous.getExpiresAt().isAfter(now)) {
                requireEthQuoteCleared(previous);
                previous.setStatus(MembershipOrder.Status.EXPIRED);
            } else pending = true;
        }
        if (pending)
            throw new AppException(ListingErrorCode.MEMBERSHIP_ORDER_PENDING);
        MembershipOrder order = new MembershipOrder();
        order.setId(UUID.randomUUID().toString().replace("-", ""));
        order.setOwnerId(ownerId);
        order.setTier(tier);
        order.setAmountVnd(quote.amountVnd());
        order.setUpgrade(quote.upgrade());
        order.setUpgradeDeadline(quote.upgrade() ? quote.upgradeDeadline() : null);
        order.setStatus(MembershipOrder.Status.PENDING);
        order.setProvider(provider);
        order.setCreatedAt(now);
        order.setExpiresAt(now.plus(15, java.time.temporal.ChronoUnit.MINUTES));
        orders.save(order);
        if (provider == MembershipOrder.Provider.ETH)
            return new Checkout(order.getId(), "", order.getAmountVnd(), provider.name(), Map.of());
        String callback = sepayReturnUrl + (sepayReturnUrl.contains("?") ? "&" : "?") + "orderId=" + order.getId();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("order_amount", String.valueOf(order.getAmountVnd()));
        fields.put("merchant", sepayMerchantId);
        fields.put("currency", "VND");
        fields.put("operation", "PURCHASE");
        fields.put("order_description", "HomeSpace membership " + tier.name());
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
        if (!o.getOwnerId().equals(ownerId)) throw new AppException(ListingErrorCode.LISTING_FORBIDDEN);
        if (o.getStatus() == MembershipOrder.Status.PENDING) {
            requireEthQuoteCleared(o);
            o.setStatus(MembershipOrder.Status.CANCELLED);
        }
        return OrderView.from(o);
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
        if (o == null || o.getProvider() != MembershipOrder.Provider.SEPAY
                || orderAmount.compareTo(BigDecimal.valueOf(o.getAmountVnd())) != 0
                || transactionAmount.compareTo(BigDecimal.valueOf(o.getAmountVnd())) != 0) return false;
        if (o.getStatus() == MembershipOrder.Status.PAID || o.getStatus() == MembershipOrder.Status.REVIEW) {
            if (!transactionId.equals(o.getTransactionNo())) {
                log.warn("SePay order {} received a second payment transaction: existing={}, incoming={}",
                        id, o.getTransactionNo(), transactionId);
                o.setStatus(MembershipOrder.Status.REVIEW);
            }
            return true;
        }
        Instant now = Instant.now();
        if (o.getStatus() != MembershipOrder.Status.PENDING || !o.getExpiresAt().isAfter(now)) {
            o.setStatus(MembershipOrder.Status.REVIEW);
        } else if (memberships.activate(o.getOwnerId(), o.getTier(), o.isUpgrade())) {
            o.setStatus(MembershipOrder.Status.PAID);
        } else {
            o.setStatus(MembershipOrder.Status.REVIEW);
        }
        o.setTransactionNo(transactionId);
        o.setPaidAt(now);
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
