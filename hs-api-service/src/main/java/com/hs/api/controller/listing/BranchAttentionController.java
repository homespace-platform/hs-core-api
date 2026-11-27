package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.CurrentUser;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.branch.BranchAttentionResponse;
import com.hs.contract.service.BranchAttentionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class BranchAttentionController {
    private final BranchAttentionService attentionService;

    // The raw parameter map keeps a repeated or empty value visible instead of silently picking one.
    @GetMapping("/branches/{branchId}/attention")
    public ResponseEntity<ApiResponse<BranchAttentionResponse>> attention(@PathVariable String branchId,
            @RequestParam MultiValueMap<String, String> query) {
        String userId = CurrentUser.requireUserId();
        int page = number(query.get("page"), 0, 0, Integer.MAX_VALUE);
        int size = number(query.get("size"), 20, 1, 100);
        return reply(attentionService.attention(branchId, userId, page, size));
    }

    @GetMapping("/branches/attention-summary")
    public ResponseEntity<ApiResponse<BranchAttentionResponse.Summary>> summary() {
        return reply(attentionService.summary(CurrentUser.requireUserId()));
    }

    private static <T> ResponseEntity<ApiResponse<T>> reply(T result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.<T>builder().result(result).build());
    }

    private static int number(List<String> values, int fallback, int min, int max) {
        if (values == null) return fallback;
        if (values.size() == 1 && values.getFirst().matches("[0-9]{1,10}")) {
            long value = Long.parseLong(values.getFirst());
            if (value >= min && value <= max) return (int) value;
        }
        throw new AppException(ErrorCode.INVALID_REQUEST);
    }
}
