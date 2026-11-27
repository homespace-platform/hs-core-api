package com.hs.api.controller.payment;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.*;
import com.hs.payment.receipt.*;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Buyer-only membership payment receipts; the service denies every other user. */
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping({"/membership/receipts", "/api/v1/membership/receipts"})
public class MembershipReceiptController {
  private final MembershipReceiptService receipts;

  private String actor() {
    var context = UserContextHolder.get();
    if (context == null || context.userId() == null || context.userId().isBlank())
      throw new AppException(ErrorCode.UNAUTHENTICATED);
    return context.userId();
  }

  @GetMapping
  public PageResponse<MembershipReceiptView> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size) {
    return receipts.list(actor(), page, size);
  }

  @GetMapping("/{id}")
  public ApiResponse<MembershipReceiptView> get(@PathVariable String id) {
    return ApiResponse.<MembershipReceiptView>builder().result(receipts.get(id, actor())).build();
  }

  @GetMapping("/by-order/{orderId}")
  public ApiResponse<MembershipReceiptView> byOrder(@PathVariable String orderId) {
    return ApiResponse.<MembershipReceiptView>builder()
        .result(receipts.byOrder(orderId, actor()))
        .build();
  }

  @PostMapping("/{id}/retry")
  public ApiResponse<MembershipReceiptView> retry(@PathVariable String id) {
    return ApiResponse.<MembershipReceiptView>builder().result(receipts.retry(id, actor())).build();
  }

  // Same JSON-wrapped bytes as payment receipts: download managers can intercept PDF responses.
  @GetMapping(value = "/{id}/content", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<byte[]>> content(@PathVariable String id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.<byte[]>builder().result(receipts.pdf(id, actor())).build());
  }
}
