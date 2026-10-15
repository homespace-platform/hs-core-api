package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.amendment.*;
import com.hs.contract.service.ContractAmendmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping({"/contracts/{id}/amendments", "/api/v1/contracts/{id}/amendments"})
public class ContractAmendmentController {
    private final ContractAmendmentService service;
    private String actor() { var c = UserContextHolder.get(); if (c == null || c.userId() == null) throw new AppException(ErrorCode.UNAUTHENTICATED); return c.userId(); }
    private ApiResponse<AmendmentResponse> result(AmendmentResponse response) { return ApiResponse.<AmendmentResponse>builder().result(response).build(); }
    @GetMapping public ApiResponse<AmendmentResponse> get(@PathVariable String id) { return result(service.get(id, actor())); }
    @GetMapping("/options") public ApiResponse<com.hs.contract.service.AmendmentOptionsService.Options> options(@PathVariable String id) { return ApiResponse.<com.hs.contract.service.AmendmentOptionsService.Options>builder().result(service.options(id,actor())).build(); }
    @PostMapping("/preview") public ApiResponse<com.hs.contract.service.AmendmentTermsBuilder.Result> preview(@PathVariable String id, @RequestBody AmendmentPayload p) {
        return ApiResponse.<com.hs.contract.service.AmendmentTermsBuilder.Result>builder().result(service.preview(id, actor(), p)).build();
    }
    @PostMapping public ApiResponse<AmendmentResponse> propose(@PathVariable String id, @RequestBody AmendmentPayload p) { return result(service.propose(id, actor(), p)); }
    @PostMapping("/tenant-requests") public ApiResponse<AmendmentResponse> request(@PathVariable String id, @RequestBody TenantAmendmentRequest p) { return result(service.requestNegotiation(id, actor(), p)); }
    @PostMapping("/tenant-requests/{requestId}/decision") public ApiResponse<AmendmentResponse> respond(@PathVariable String id, @PathVariable String requestId, @RequestBody AmendmentDecision p) { return result(service.decideNegotiation(id, requestId, actor(), p)); }
    @GetMapping("/draft") public ApiResponse<AmendmentPayload> draft(@PathVariable String id) { return ApiResponse.<AmendmentPayload>builder().result(service.getDraft(id, actor())).build(); }
    @PutMapping("/draft") public ApiResponse<AmendmentPayload> saveDraft(@PathVariable String id, @RequestBody AmendmentPayload p) { return ApiResponse.<AmendmentPayload>builder().result(service.saveDraft(id, actor(), p)).build(); }
    @DeleteMapping("/draft") public ApiResponse<Boolean> discardDraft(@PathVariable String id) { service.discardDraft(id,actor()); return ApiResponse.<Boolean>builder().result(true).build(); }
    @PostMapping("/{proposalId}/decision") public ApiResponse<AmendmentResponse> decide(@PathVariable String id, @PathVariable String proposalId, @RequestBody AmendmentDecision p) { return result(service.decide(id, proposalId, actor(), p)); }
}
