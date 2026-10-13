package com.hs.api.controller.contract;

import com.hs.common.context.UserContextHolder;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.billing.*;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.service.ContractLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping({"/contracts/{id}/lifecycle","/api/v1/contracts/{id}/lifecycle"})
public class ContractLifecycleController {
    private final ContractLifecycleService lifecycle;
    private String actor() {
        var c=UserContextHolder.get();
        if(c==null || c.userId()==null || c.userId().isBlank()) throw new AppException(ErrorCode.UNAUTHENTICATED);
        return c.userId();
    }
    private ApiResponse<ContractLifecycleResponse> result(ContractLifecycleResponse response) {
        return ApiResponse.<ContractLifecycleResponse>builder().result(response).build();
    }
    @GetMapping public ApiResponse<ContractLifecycleResponse> get(@PathVariable String id) { return result(lifecycle.get(id,actor())); }
    @PostMapping("/renewals") public ApiResponse<ContractLifecycleResponse> request(@PathVariable String id,@Valid @RequestBody RenewalPayload p) {
        return result(lifecycle.requestRenewal(id,actor(),p));
    }
    @PostMapping("/renewals/{renewalId}/approve") public ApiResponse<ContractLifecycleResponse> approve(@PathVariable String id,@PathVariable String renewalId) {
        return result(lifecycle.decideRenewal(id,renewalId,actor(),true));
    }
    @PostMapping("/renewals/{renewalId}/reject") public ApiResponse<ContractLifecycleResponse> reject(@PathVariable String id,@PathVariable String renewalId) {
        return result(lifecycle.decideRenewal(id,renewalId,actor(),false));
    }
    @PostMapping("/closure/propose") public ApiResponse<ContractLifecycleResponse> propose(@PathVariable String id,@Valid @RequestBody ClosurePayload p) {
        return result(lifecycle.proposeClosure(id,actor(),p));
    }
    @PostMapping("/final-costs") public ApiResponse<ContractLifecycleResponse> finalCosts(@PathVariable String id,@Valid @RequestBody FinalCostsPayload p) {
        return result(lifecycle.issueFinalCosts(id,actor(),p));
    }
    @PostMapping("/closure/accept") public ApiResponse<ContractLifecycleResponse> accept(@PathVariable String id,@RequestBody AcceptClosurePayload p) {
        return result(lifecycle.acceptClosure(id,actor(),p));
    }
    @PostMapping("/closure/dispute") public ApiResponse<ContractLifecycleResponse> dispute(@PathVariable String id,@RequestBody DisputeClosurePayload p) {
        if(p==null) throw new AppException(ContractErrorCode.LIFECYCLE_INVALID);
        return result(lifecycle.disputeClosure(id,actor(),p));
    }
}
