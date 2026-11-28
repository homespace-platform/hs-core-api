package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.admin.AdminDashboardResponse.Health;
import com.hs.contract.service.admin.AdminDashboardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@PreAuthorize("hasAuthority('ADMIN') and ("
        + "hasAuthority('SYSTEM_HEALTH_VIEW') or hasAuthority('PAYMENT_VIEW') or "
        + "hasAuthority('KYC_VIEW') or hasAuthority('COMPLAINT_VIEW') or "
        + "hasAuthority('STATISTICS_VIEW'))")
@RequestMapping({"/admin/system-health","/api/v1/admin/system-health"})
public class AdminSystemHealthController {
    private final AdminDashboardService service;
    @GetMapping public ResponseEntity<ApiResponse<Health>> health(HttpServletRequest request){
        AdminDashboardService.requireAdmin();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.<Health>builder().result(service.health(AdminDashboardController.fresh(request))).build());}
}
