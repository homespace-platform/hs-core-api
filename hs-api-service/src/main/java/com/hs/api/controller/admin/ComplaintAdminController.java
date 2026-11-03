package com.hs.api.controller.admin;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.*;
import com.hs.contract.dto.complaint.*;
import com.hs.contract.service.ComplaintCaseService;
import com.hs.payment.model.constant.DisputeCaseStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Validated @PreAuthorize("hasAuthority('ADMIN')")
@RequestMapping({"/admin/complaints","/api/v1/admin/complaints"})
public class ComplaintAdminController {
    private final ComplaintCaseService service;
    @GetMapping public PageResponse<ComplaintView> list(@RequestParam(required=false) DisputeCaseStatus status,@RequestParam(required=false) ComplaintCategory category,
        @RequestParam(required=false) Boolean overdue,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size){return service.adminList(actor(),status,category,overdue,page,size);}
    @GetMapping("/{id}") public ApiResponse<ComplaintView> get(@PathVariable String id){return result(service.adminGet(id,actor()));}
    @PostMapping("/{id}/actions/{action}") public ApiResponse<ComplaintView> act(@PathVariable String id,@PathVariable String action,@Valid @RequestBody ComplaintAction p){return result(service.adminAct(id,actor(),action,p));}
    private ApiResponse<ComplaintView> result(ComplaintView value){return ApiResponse.<ComplaintView>builder().result(value).build();}
    private String actor(){var c=UserContextHolder.get();if(c==null||c.userId()==null||c.userId().isBlank())throw new AppException(ErrorCode.UNAUTHENTICATED);return c.userId();}
}
