package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.CurrentUser;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.dashboard.LandlordDashboardResponse;
import com.hs.contract.service.LandlordDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Landlord dashboard. The owner is the verified caller; no user, branch or role comes from the request. */
@RestController
@RequiredArgsConstructor
public class LandlordDashboardController {
    private static final Pattern MONTH = Pattern.compile("(?!0000)[0-9]{4}-(0[1-9]|1[0-2])");

    private final LandlordDashboardService dashboard;

    // The raw parameter map keeps a repeated or empty value visible instead of silently picking one.
    @GetMapping("/dashboard/landlord/summary")
    public ResponseEntity<ApiResponse<LandlordDashboardResponse>> summary(
            @RequestParam MultiValueMap<String, String> query) {
        String userId = CurrentUser.requireUserId();
        LandlordDashboardResponse result = dashboard.summary(userId, month(query.get("month")),
                include(query.get("include")));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.<LandlordDashboardResponse>builder().result(result).build());
    }

    private static YearMonth month(List<String> values) {
        if (values == null) return null;
        if (values.size() != 1 || !MONTH.matcher(values.getFirst()).matches()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        return YearMonth.parse(values.getFirst());
    }

    private static Set<String> include(List<String> values) {
        if (values == null) return null;
        if (values.size() != 1) throw new AppException(ErrorCode.INVALID_REQUEST);
        Set<String> keys = new LinkedHashSet<>(List.of(values.getFirst().split(",", -1)));
        if (!LandlordDashboardService.GROUPS.containsAll(keys)) throw new AppException(ErrorCode.INVALID_REQUEST);
        return keys;
    }
}
