package com.hs.api.controller.admin;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.admin.AdminDashboardResponse;
import com.hs.contract.service.admin.AdminDashboardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@PreAuthorize("hasAuthority('ADMIN') and ("
        + "hasAuthority('LISTING_VIEW') or hasAuthority('USER_VIEW') or "
        + "hasAuthority('KYC_VIEW') or hasAuthority('COMPLAINT_VIEW') or "
        + "hasAuthority('INCIDENT_VIEW') or hasAuthority('PAYMENT_VIEW') or "
        + "hasAuthority('CONTRACT_VIEW') or hasAuthority('STATISTICS_VIEW') or "
        + "hasAuthority('SYSTEM_HEALTH_VIEW'))")
@RequestMapping({"/admin/dashboard","/api/v1/admin/dashboard"})
public class AdminDashboardController {
    private final AdminDashboardService service;
    @GetMapping("/overview") public ResponseEntity<ApiResponse<AdminDashboardResponse>> overview(HttpServletRequest request){
        AdminDashboardService.requireAdmin();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.<AdminDashboardResponse>builder().result(service.overview(fresh(request))).build());}
    static boolean fresh(HttpServletRequest request){var p=request.getParameterMap();if(p.isEmpty())return false;
        if(p.size()!=1||!p.containsKey("fresh")||p.get("fresh").length!=1||!java.util.Set.of("true","false").contains(p.get("fresh")[0]))throw new AppException(ErrorCode.INVALID_REQUEST);
        return Boolean.parseBoolean(p.get("fresh")[0]);}
}
