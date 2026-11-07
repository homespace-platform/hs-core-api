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

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping({"/payment-receipts", "/api/v1/payment-receipts"})
public class PaymentReceiptController {
  private final PaymentReceiptService receipts;

  private String actor() {
    var context = UserContextHolder.get();
    if (context == null || context.userId() == null || context.userId().isBlank())
      throw new AppException(ErrorCode.UNAUTHENTICATED);
    return context.userId();
  }

  @GetMapping
  public PageResponse<PaymentReceiptView> list(
      @RequestParam(required = false) @Pattern(regexp = "LANDLORD|TENANT") String role,
      @RequestParam(required = false) String contractId,
      @RequestParam(required = false) String rentalRequestId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size) {
    return receipts.list(actor(), role, contractId, rentalRequestId, page, size);
  }

  @GetMapping("/{id}")
  public ApiResponse<PaymentReceiptView> get(@PathVariable String id) {
    return ApiResponse.<PaymentReceiptView>builder().result(receipts.get(id, actor())).build();
  }

  @GetMapping("/by-payment/{id}")
  public ApiResponse<PaymentReceiptView> byPayment(@PathVariable String id) {
    return ApiResponse.<PaymentReceiptView>builder()
        .result(receipts.byPayment(id, actor()))
        .build();
  }

  @PostMapping("/{id}/retry")
  public ApiResponse<PaymentReceiptView> retry(@PathVariable String id) {
    return ApiResponse.<PaymentReceiptView>builder().result(receipts.retry(id, actor())).build();
  }

  // Download managers can intercept PDF responses before the web app receives them.
  @GetMapping(value = "/{id}/content", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<byte[]>> content(@PathVariable String id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.<byte[]>builder().result(receipts.pdf(id, actor())).build());
  }

  @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> pdf(
      @PathVariable String id, @RequestParam(defaultValue = "false") boolean download) {
    String actor = actor();
    var receipt = receipts.get(id, actor);
    byte[] pdf = receipts.pdf(id, actor);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(receipt.receiptNumber() + ".pdf")
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(pdf);
  }
}
