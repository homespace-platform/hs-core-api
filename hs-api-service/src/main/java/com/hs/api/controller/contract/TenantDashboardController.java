package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.CurrentUser;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.tenant.TenantDashboardResponse;
import com.hs.contract.service.TenantDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TenantDashboardController {
    private final TenantDashboardService service;

    /** The user is always the verified caller. The raw map makes any unknown or repeated parameter a 400. */
    @GetMapping({"/dashboard/tenant/summary", "/api/v1/dashboard/tenant/summary"})
    public ResponseEntity<ApiResponse<TenantDashboardResponse>> summary(
            @RequestParam MultiValueMap<String, String> query) {
        String userId = CurrentUser.requireUserId();
        if (!List.of("spendMonths").containsAll(query.keySet())) throw new AppException(ErrorCode.INVALID_REQUEST);
        var values = query.get("spendMonths");
        int months = 12;
        if (values != null) {
            if (values.size() != 1 || !values.getFirst().matches("6|12")) throw new AppException(ErrorCode.INVALID_REQUEST);
            months = Integer.parseInt(values.getFirst());
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.<TenantDashboardResponse>builder().result(service.summary(userId, months)).build());
    }
}
