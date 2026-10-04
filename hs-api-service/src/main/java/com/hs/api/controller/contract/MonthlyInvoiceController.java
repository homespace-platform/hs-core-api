package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.billing.IssueMonthlyInvoiceRequest;
import com.hs.contract.dto.billing.MonthlyInvoiceResponse;
import com.hs.contract.service.MonthlyBillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping({"/monthly-invoices", "/api/v1/monthly-invoices"})
@RequiredArgsConstructor
public class MonthlyInvoiceController {
    private final MonthlyBillingService billing;

    @GetMapping("/contracts/{contractId}")
    public ApiResponse<List<MonthlyInvoiceResponse>> list(@PathVariable String contractId) {
        return ApiResponse.<List<MonthlyInvoiceResponse>>builder()
                .result(billing.list(contractId, requireUserId())).build();
    }

    @PostMapping("/{invoiceId}/issue")
    public ApiResponse<MonthlyInvoiceResponse> issue(@PathVariable String invoiceId,
                                                      @RequestBody @Valid IssueMonthlyInvoiceRequest request) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Đã phát hành hóa đơn cho người thuê")
                .result(billing.issue(invoiceId, requireUserId(), request)).build();
    }

    @PostMapping("/{invoiceId}/prepare")
    public ApiResponse<MonthlyInvoiceResponse> prepare(@PathVariable String invoiceId,
                                                        @RequestBody @Valid IssueMonthlyInvoiceRequest request) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Đã lưu chỉ số và khoản phát sinh để phát hành tự động")
                .result(billing.prepare(invoiceId, requireUserId(), request)).build();
    }

    private String requireUserId() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank())
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        return context.userId();
    }
}
