package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.billing.IssueMonthlyInvoiceRequest;
import com.hs.contract.dto.billing.MonthlyInvoiceResponse;
import com.hs.contract.dto.billing.CreateOverdueActionRequest;
import com.hs.contract.dto.billing.AcknowledgeOverdueActionRequest;
import com.hs.contract.dto.billing.AcceptTerminationRequest;
import com.hs.contract.dto.billing.CompleteTerminationRequest;
import com.hs.contract.dto.billing.ForceTerminationRequest;
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

    @PostMapping("/{invoiceId}/overdue-actions")
    public ApiResponse<MonthlyInvoiceResponse> recordOverdueAction(@PathVariable String invoiceId,
                                                                    @RequestBody CreateOverdueActionRequest request) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Đã ghi nhận phương án xử lý quá hạn; hợp đồng và tiền cọc không tự thay đổi")
                .result(billing.recordOverdueAction(invoiceId, requireUserId(), request)).build();
    }

    @PostMapping("/{invoiceId}/defer-to-next-period")
    public ApiResponse<MonthlyInvoiceResponse> deferToNextPeriod(@PathVariable String invoiceId) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Đã chốt số nợ; hóa đơn kỳ kế tiếp sẽ cộng dồn và QR cũ sẽ bị hủy khi chuyển nợ")
                .result(billing.deferToNextPeriod(invoiceId, requireUserId())).build();
    }

    @PostMapping("/{invoiceId}/termination/propose")
    public ApiResponse<MonthlyInvoiceResponse> proposeTermination(@PathVariable String invoiceId) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Đã gửi đề nghị chấm dứt; chưa thay đổi hợp đồng, cọc hoặc tin đăng")
                .result(billing.proposeMutualTermination(invoiceId, requireUserId())).build();
    }

    @PostMapping("/{invoiceId}/termination/accept")
    public ApiResponse<MonthlyInvoiceResponse> acceptTermination(@PathVariable String invoiceId,
                                                                 @RequestBody AcceptTerminationRequest request) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Đã ghi nhận người thuê đồng ý chấm dứt sớm và cọc thuộc chủ nhà sau bàn giao")
                .result(billing.acceptMutualTermination(invoiceId, requireUserId(), request)).build();
    }

    @PostMapping("/{invoiceId}/termination/decline")
    public ApiResponse<MonthlyInvoiceResponse> declineTermination(@PathVariable String invoiceId) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Đã ghi nhận người thuê không đồng ý chấm dứt sớm; hợp đồng tiếp tục hiệu lực")
                .result(billing.declineMutualTermination(invoiceId, requireUserId())).build();
    }

    @PostMapping("/{invoiceId}/termination/withdraw")
    public ApiResponse<MonthlyInvoiceResponse> withdrawTermination(@PathVariable String invoiceId) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Chủ nhà đã rút đề nghị chấm dứt; hợp đồng tiếp tục hiệu lực")
                .result(billing.withdrawMutualTermination(invoiceId, requireUserId())).build();
    }

    @PostMapping("/{invoiceId}/termination/complete")
    public ApiResponse<MonthlyInvoiceResponse> completeTermination(@PathVariable String invoiceId,
                                                                   @RequestBody CompleteTerminationRequest request) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Đã chấm dứt hợp đồng, quyết toán cọc cho chủ nhà và mở lại tin đăng")
                .result(billing.completeMutualTermination(invoiceId, requireUserId(), request)).build();
    }

    @PostMapping("/{invoiceId}/termination/force-after-decline")
    public ApiResponse<MonthlyInvoiceResponse> forceTermination(@PathVariable String invoiceId,
                                                                @RequestBody ForceTerminationRequest request) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .result(billing.forceTerminationAfterDecline(invoiceId, requireUserId(), request)).build();
    }

    @PostMapping("/{invoiceId}/overdue-actions/{actionId}/acknowledge")
    public ApiResponse<MonthlyInvoiceResponse> acknowledgeOverdueAction(@PathVariable String invoiceId,
                                                                         @PathVariable String actionId,
                                                                         @RequestBody AcknowledgeOverdueActionRequest request) {
        return ApiResponse.<MonthlyInvoiceResponse>builder()
                .message("Đã ghi nhận phản hồi; đây không phải chấp thuận sửa hợp đồng")
                .result(billing.acknowledgeOverdueAction(invoiceId, actionId, requireUserId(), request)).build();
    }

    private String requireUserId() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank())
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        return context.userId();
    }
}
