package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.user.service.kyc.AdminKycQueueService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
@RequestMapping({"/admin/kyc","/api/v1/admin/kyc"})
@PreAuthorize("hasAuthority('ADMIN') and hasAuthority('KYC_VIEW')")
public class AdminKycQueueController {
    private final AdminKycQueueService service;
    @GetMapping public ResponseEntity<ApiResponse<AdminKycQueueService.Page>> list(HttpServletRequest request){
        AdminKycQueueService.authorize();var q=AdminKycQueueService.Query.parse(request.getParameterMap(),true);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.<AdminKycQueueService.Page>builder().result(service.list(q)).build());}
    @GetMapping("/counts") public ResponseEntity<ApiResponse<List<Map<String,Object>>>> counts(HttpServletRequest request){
        AdminKycQueueService.authorize();var q=AdminKycQueueService.Query.parse(request.getParameterMap(),false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.<List<Map<String,Object>>>builder().result(service.counts(q)).build());}
}
