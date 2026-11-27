package com.hs.api.controller.admin;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.payment.membership.MembershipOverpayment;
import com.hs.payment.membership.MembershipOverpaymentAdminService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Refund list for membership overpayments (second payment / unappliable payment). Not an order reconciliation. */
@RestController
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAuthority('ADMIN') and hasAuthority('MEMBERSHIP_REFUND_MANAGE')")
@RequestMapping("/membership/admin/overpayments")
public class MembershipOverpaymentAdminController {
    private final MembershipOverpaymentAdminService service;

    public record RefundedRequest(@Size(max = 500) String note) {}

    @GetMapping
    public PageResponse<MembershipOverpaymentAdminService.View> list(
            @RequestParam(defaultValue = "REFUND_PENDING") MembershipOverpayment.Status status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new PageResponse<>(service.list(status, page, size));
    }

    @PostMapping("/{id}/refunded")
    public ApiResponse<MembershipOverpaymentAdminService.View> markRefunded(
            @PathVariable String id, @RequestBody(required = false) @jakarta.validation.Valid RefundedRequest body) {
        var actor = UserContextHolder.get();
        if (actor == null || actor.userId() == null || actor.userId().isBlank())
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        return ApiResponse.<MembershipOverpaymentAdminService.View>builder()
                .result(service.markRefunded(id, actor.userId(), body == null ? null : body.note())).build();
    }
}
