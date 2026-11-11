package com.hs.api.controller.payment;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.membership.MembershipTier;
import com.hs.payment.membership.MembershipCheckoutService;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.dto.EthPaymentDtos;
import com.hs.payment.service.EthPaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/membership", "/api/v1/membership"})
@RequiredArgsConstructor
public class MembershipController {
    private static final ObjectMapper IPN_MAPPER = new ObjectMapper();
    private final MembershipService memberships;
    private final MembershipCheckoutService checkout;
    private final EthPaymentService ethPayment;

    public record CheckoutRequest(@NotNull MembershipTier tier, @NotNull MembershipOrder.Provider provider) {}

    @GetMapping("/plans")
    public ApiResponse<List<MembershipService.Plan>> plans() {
        return ApiResponse.<List<MembershipService.Plan>>builder().result(memberships.plans()).build();
    }

    @GetMapping("/me")
    public ApiResponse<MembershipService.View> me() {
        return ApiResponse.<MembershipService.View>builder().result(memberships.view(requireUserId())).build();
    }

    @GetMapping("/quote")
    public ApiResponse<MembershipService.Quote> quote(@RequestParam MembershipTier tier) {
        return ApiResponse.<MembershipService.Quote>builder().result(memberships.quote(requireUserId(), tier)).build();
    }

    @PostMapping("/checkout")
    public ApiResponse<MembershipCheckoutService.Checkout> checkout(@RequestBody @Valid CheckoutRequest request) {
        return ApiResponse.<MembershipCheckoutService.Checkout>builder()
                .result(checkout.checkout(requireUserId(), request.tier(), request.provider())).build();
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<MembershipCheckoutService.OrderView> order(@PathVariable String id) {
        return ApiResponse.<MembershipCheckoutService.OrderView>builder()
                .result(checkout.order(requireUserId(), id)).build();
    }

    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<MembershipCheckoutService.OrderView> cancel(@PathVariable String id) {
        return ApiResponse.<MembershipCheckoutService.OrderView>builder()
                .result(checkout.cancel(requireUserId(), id)).build();
    }

    @GetMapping("/orders/{id}/eth/challenge")
    public ApiResponse<EthPaymentDtos.Challenge> ethChallenge(@PathVariable String id, @RequestParam String wallet) {
        return ApiResponse.<EthPaymentDtos.Challenge>builder()
                .result(ethPayment.challenge(id, requireUserId(), wallet)).build();
    }

    @PostMapping("/orders/{id}/eth/quote")
    public ApiResponse<EthPaymentDtos.Quote> ethQuote(@PathVariable String id,
            @RequestBody @Valid EthPaymentDtos.CreateQuote request) {
        return ApiResponse.<EthPaymentDtos.Quote>builder()
                .result(ethPayment.quote(id, requireUserId(), request)).build();
    }

    @PostMapping("/orders/{id}/eth/settle")
    public ApiResponse<MembershipCheckoutService.OrderView> ethSettle(@PathVariable String id,
            @RequestBody @Valid EthPaymentDtos.Settle request) {
        return ApiResponse.<MembershipCheckoutService.OrderView>builder()
                .result(ethPayment.settle(id, requireUserId(), request)).build();
    }

    @GetMapping("/orders/{id}/eth/status")
    public ApiResponse<EthPaymentDtos.Status> ethStatus(@PathVariable String id) {
        return ApiResponse.<EthPaymentDtos.Status>builder()
                .result(ethPayment.status(id, requireUserId())).build();
    }

    @PostMapping("/sepay/ipn")
    public ResponseEntity<Map<String, Boolean>> sepayIpn(
            @RequestHeader(value = "X-Secret-Key", required = false) String secret,
            @RequestBody String body) {
        if (!checkout.sepayAuthenticated(secret))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false));
        JsonNode payload;
        try {
            payload = IPN_MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false));
        }
        if (!checkout.sepayIpn(payload))
            return ResponseEntity.badRequest().body(Map.of("success", false));
        return ResponseEntity.ok(Map.of("success", true));
    }

    private String requireUserId() {
        var ctx = UserContextHolder.get();
        if (ctx == null || ctx.userId() == null || ctx.userId().isBlank())
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        return ctx.userId();
    }
}
