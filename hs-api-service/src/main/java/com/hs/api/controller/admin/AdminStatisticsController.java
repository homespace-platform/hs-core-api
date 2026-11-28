package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.statistics.*;
import com.hs.contract.service.statistics.AdminStatisticsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/admin/statistics","/api/v1/admin/statistics"})
@PreAuthorize("hasAuthority('ADMIN') and hasAuthority('STATISTICS_VIEW')")
public class AdminStatisticsController {
    private final AdminStatisticsService service;
    private final BillingTime time;

    @GetMapping
    public ResponseEntity<ApiResponse<AdminStatisticsResponse>> statistics(HttpServletRequest request) {
        AdminStatisticsService.authorize();
        var query=AdminStatisticsQuery.parse(request.getParameterMap(),time.today());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                ApiResponse.<AdminStatisticsResponse>builder().result(service.statistics(query)).build());
    }
}
