package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.CurrentUser;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.branch.BranchOverviewResponse;
import com.hs.contract.service.BranchOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
public class BranchOverviewController {
    private static final Pattern MONTH = Pattern.compile("(?!0000)[0-9]{4}-(0[1-9]|1[0-2])");

    private final BranchOverviewService overviewService;

    // The raw parameter map keeps a repeated or empty month visible instead of silently picking one.
    @GetMapping("/branches/{branchId}/overview")
    public ResponseEntity<ApiResponse<BranchOverviewResponse>> overview(@PathVariable String branchId,
            @RequestParam MultiValueMap<String, String> query) {
        String userId = CurrentUser.requireUserId();
        BranchOverviewResponse result = overviewService.overview(branchId, userId, month(query.get("month")));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.<BranchOverviewResponse>builder().result(result).build());
    }

    private static YearMonth month(List<String> values) {
        if (values == null) return null;
        if (values.size() != 1 || !MONTH.matcher(values.getFirst()).matches()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        return YearMonth.parse(values.getFirst());
    }
}
