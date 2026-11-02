package com.hs.api.controller.admin;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.*;
import com.hs.payment.dto.*;
import com.hs.payment.model.constant.DisputeCaseStatus;
import com.hs.payment.service.PaymentDisputeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Validated
@RequestMapping({"/admin/payment-disputes","/api/v1/admin/payment-disputes"})
@PreAuthorize("hasAuthority('ADMIN')")
public class PaymentDisputeAdminController {
    private final PaymentDisputeService service;
    @GetMapping public PageResponse<DisputeCaseView> list(@RequestParam(required=false) DisputeCaseStatus status,
            @RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size){return service.adminList(actor(),status,page,size);}
    @GetMapping("/{id}") public ApiResponse<DisputeCaseView> get(@PathVariable String id){return result(service.adminGet(id,actor()));}
    @PostMapping("/{id}/actions/{action}") public ApiResponse<DisputeCaseView> act(@PathVariable String id,@PathVariable String action,
            @Valid @RequestBody DisputeCaseAction request){return result(service.adminAct(id,actor(),action,request));}
    private ApiResponse<DisputeCaseView> result(DisputeCaseView value){return ApiResponse.<DisputeCaseView>builder().result(value).build();}
    private String actor(){var c=UserContextHolder.get();if(c==null||c.userId()==null||c.userId().isBlank())throw new AppException(ErrorCode.UNAUTHENTICATED);return c.userId();}
}
