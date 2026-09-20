package com.hs.api.controller.payment;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.payment.dto.*;
import com.hs.payment.service.PaymentRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/payment-requests")
@RequiredArgsConstructor
public class PaymentRequestController {

    private final PaymentRequestService paymentRequestService;

    private String requireUserId() {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null || ctx.userId() == null || ctx.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return ctx.userId();
    }

    @GetMapping("/{id}")
    public ApiResponse<PaymentRequestResponse> getPaymentRequest(@PathVariable String id) {
        String actorId = requireUserId();
        return ApiResponse.<PaymentRequestResponse>builder()
                .result(paymentRequestService.getPaymentRequest(id, actorId))
                .build();
    }

    @GetMapping("/mine")
    public ApiResponse<List<PaymentRequestResponse>> getMyPaymentRequests() {
        String actorId = requireUserId();
        return ApiResponse.<List<PaymentRequestResponse>>builder()
                .result(paymentRequestService.getMyPaymentRequests(actorId))
                .build();
    }

    @PostMapping("/{id}/report-transfer")
    public ApiResponse<PaymentRequestResponse> reportTransfer(
            @PathVariable String id,
            @RequestBody @Valid ReportTransferRequest request) {
        String actorId = requireUserId();
        return ApiResponse.<PaymentRequestResponse>builder()
                .message("Đã ghi nhận khai báo chuyển khoản. Đang chờ chủ nhà xác nhận.")
                .result(paymentRequestService.reportTransfer(id, actorId, request))
                .build();
    }

    @PostMapping("/{id}/confirm-receipt")
    public ApiResponse<PaymentRequestResponse> confirmReceipt(@PathVariable String id) {
        String actorId = requireUserId();
        return ApiResponse.<PaymentRequestResponse>builder()
                .message("Bạn đã xác nhận nhận đủ tiền.")
                .result(paymentRequestService.confirmReceipt(id, actorId))
                .build();
    }

    @PostMapping("/{id}/reject-receipt")
    public ApiResponse<PaymentRequestResponse> rejectReceipt(
            @PathVariable String id,
            @RequestBody @Valid RejectReceiptRequest request) {
        String actorId = requireUserId();
        return ApiResponse.<PaymentRequestResponse>builder()
                .message("Đã từ chối xác nhận thanh toán.")
                .result(paymentRequestService.rejectReceipt(id, actorId, request))
                .build();
    }

    @PostMapping("/{id}/dispute")
    public ApiResponse<PaymentRequestResponse> dispute(
            @PathVariable String id,
            @RequestBody @Valid DisputePaymentRequest request) {
        String actorId = requireUserId();
        return ApiResponse.<PaymentRequestResponse>builder()
                .message("Khoản thanh toán đã được chuyển sang trạng thái chờ đối soát.")
                .result(paymentRequestService.dispute(id, actorId, request))
                .build();
    }

    @GetMapping("/{id}/events")
    public ApiResponse<List<PaymentEventResponse>> getEvents(@PathVariable String id) {
        String actorId = requireUserId();
        return ApiResponse.<List<PaymentEventResponse>>builder()
                .result(paymentRequestService.getEvents(id, actorId))
                .build();
    }
}
