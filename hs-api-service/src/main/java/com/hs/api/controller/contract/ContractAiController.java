package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.service.ai.AiContractQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only contract queries for the AI assistant. The caller is always the authenticated user from the
 * Gateway-verified context: there is deliberately no user/owner parameter, and no endpoint here mutates
 * anything or triggers lifecycle/billing synchronisation. All parameters are validated by the service so
 * invalid input yields a fixed message (no echo of values into errors or logs).
 */
@RestController
@RequestMapping({"/contracts/ai", "/api/v1/contracts/ai"})
@RequiredArgsConstructor
public class ContractAiController {
    private final AiContractQueryService service;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestParam(required = false) String role,
                                                    @RequestParam(required = false) Integer expiringWithinDays) {
        return ok(service.summarize(userId(), role, expiringWithinDays));
    }

    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestParam(required = false) String role,
                                                   @RequestParam(required = false) List<String> status,
                                                   @RequestParam(required = false) String leasePhase,
                                                   @RequestParam(required = false) Integer expiringWithinDays,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) String sort,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return ok(service.search(userId(), role, status, leasePhase, expiringWithinDays, q, sort, page, size));
    }

    @GetMapping("/items/{contractId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String contractId) {
        return ok(service.detail(userId(), contractId));
    }

    @GetMapping("/items/{contractId}/terms")
    public ApiResponse<Map<String, Object>> terms(@PathVariable String contractId,
                                                  @RequestParam(required = false) List<String> groups,
                                                  @RequestParam(required = false) String asOf) {
        return ok(service.terms(userId(), contractId, groups, asOf));
    }

    @GetMapping("/items/{contractId}/amendments")
    public ApiResponse<Map<String, Object>> amendments(@PathVariable String contractId) {
        return ok(service.amendments(userId(), contractId));
    }

    @GetMapping("/items/{contractId}/lifecycle")
    public ApiResponse<Map<String, Object>> lifecycle(@PathVariable String contractId) {
        return ok(service.lifecycle(userId(), contractId));
    }

    @GetMapping("/items/{contractId}/financial")
    public ApiResponse<Map<String, Object>> financial(@PathVariable String contractId,
                                                      @RequestParam(required = false) String invoices,
                                                      @RequestParam(required = false) Integer limit) {
        return ok(service.financial(userId(), contractId, invoices, limit));
    }

    private static ApiResponse<Map<String, Object>> ok(Map<String, Object> result) {
        return ApiResponse.<Map<String, Object>>builder().result(result).build();
    }

    private static String userId() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank())
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        return context.userId();
    }
}
