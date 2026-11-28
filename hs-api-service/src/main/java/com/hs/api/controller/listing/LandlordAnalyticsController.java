package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.CurrentUser;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.analytics.LandlordAnalyticsQuery;
import com.hs.contract.dto.analytics.LandlordAnalyticsResponse;
import com.hs.contract.service.analytics.LandlordAnalyticsService;
import com.hs.contract.service.analytics.LandlordAnalyticsExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Landlord analytics; the owner is always the verified caller, never a request value. */
@RestController
@RequiredArgsConstructor
public class LandlordAnalyticsController {
    private static final Pattern MONTH = Pattern.compile("(?!0000)[0-9]{4}-(0[1-9]|1[0-2])");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,36}");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,5}");

    private final LandlordAnalyticsService service;
    private final LandlordAnalyticsExportService exports;

    // The raw parameter map keeps a repeated or empty parameter visible instead of silently picking one.
    @GetMapping("/dashboard/analytics")
    public ResponseEntity<ApiResponse<LandlordAnalyticsResponse>> analytics(
            @RequestParam MultiValueMap<String, String> query) {
        String userId = CurrentUser.requireUserId();
        LandlordAnalyticsResponse result = service.analytics(userId, parse(query));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.<LandlordAnalyticsResponse>builder().result(result).build());
    }

    @GetMapping("/dashboard/analytics/export")
    public ResponseEntity<byte[]> export(@RequestParam MultiValueMap<String, String> query) {
        String owner = CurrentUser.requireUserId();
        String report = single(query, "report", null);
        String format = single(query, "format", null);
        String granularity = single(query, "granularity", null);
        if (report == null || format == null) throw new AppException(ErrorCode.INVALID_REQUEST);
        var file = exports.export(owner, parse(query), report, format, granularity == null ? "receipt" : granularity);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    private static LandlordAnalyticsQuery parse(MultiValueMap<String, String> query) {
        String noLeads = single(query, "noLeadsOnly", null);
        if (noLeads != null && !noLeads.equals("true") && !noLeads.equals("false")) throw new AppException(ErrorCode.INVALID_REQUEST);
        return new LandlordAnalyticsQuery(
                month(single(query, "from", MONTH)), month(single(query, "to", MONTH)),
                single(query, "branchId", ID), single(query, "listingId", ID), include(single(query, "include", null)),
                number(single(query, "listingPage", NUMBER)), number(single(query, "listingSize", NUMBER)),
                "true".equals(noLeads));
    }

    private static String single(MultiValueMap<String, String> query, String name, Pattern format) {
        List<String> values = query.get(name);
        if (values == null) return null;
        if (values.size() != 1 || values.getFirst().isBlank()
                || format != null && !format.matcher(values.getFirst()).matches()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        return values.getFirst();
    }

    private static YearMonth month(String value) {
        return value == null ? null : YearMonth.parse(value);
    }

    private static int number(String value) {
        if (value == null) return 0;
        int number = Integer.parseInt(value);
        if (number == 0) throw new AppException(ErrorCode.INVALID_REQUEST);
        return number;
    }

    private static Set<String> include(String value) {
        if (value == null) return Set.of();
        Set<String> sections = new LinkedHashSet<>(Arrays.asList(value.split(",", -1)));
        if (sections.size() != value.split(",", -1).length || !LandlordAnalyticsQuery.SECTIONS.containsAll(sections)) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        return sections;
    }
}
