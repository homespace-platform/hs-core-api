package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.contract.notification.OperationalIncident;
import com.hs.contract.notification.OperationalIncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@PreAuthorize("hasAuthority('ADMIN')")
@RequestMapping("/admin/operational-incidents")
public class OperationalIncidentController {
    private final OperationalIncidentService service;
    @GetMapping public ApiResponse<List<OperationalIncident>> list(){return ApiResponse.<List<OperationalIncident>>builder().result(service.list()).build();}
}
