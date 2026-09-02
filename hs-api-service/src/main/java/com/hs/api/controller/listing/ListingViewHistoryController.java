package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.response.PublicListingSummaryResponse;
import com.hs.listing.service.ListingViewHistoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
@Validated
public class ListingViewHistoryController {

    private final ListingViewHistoryService viewHistoryService;

    @PostMapping("/{listingId}")
    public ApiResponse<Void> recordView(@PathVariable String listingId) {
        String userId = currentUserId();
        viewHistoryService.recordView(userId, listingId);
        return ApiResponse.<Void>builder()
                .message("Đã ghi nhận lịch sử xem tin")
                .build();
    }

    @GetMapping
    public PageResponse<PublicListingSummaryResponse> getMyHistory(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(40) int size) {
        String userId = currentUserId();
        return viewHistoryService.getMyHistory(userId, page, size);
    }

    @GetMapping("/ids")
    public ApiResponse<List<String>> getHistoryListingIds() {
        String userId = currentUserId();
        return ApiResponse.<List<String>>builder()
                .result(viewHistoryService.getHistoryListingIds(userId))
                .build();
    }

    @GetMapping("/count")
    public ApiResponse<Long> getHistoryCount() {
        String userId = currentUserId();
        return ApiResponse.<Long>builder()
                .result(viewHistoryService.getHistoryCount(userId))
                .build();
    }

    @DeleteMapping("/{listingId}")
    public ApiResponse<Void> removeHistoryItem(@PathVariable String listingId) {
        String userId = currentUserId();
        viewHistoryService.removeHistoryItem(userId, listingId);
        return ApiResponse.<Void>builder()
                .message("Đã xóa tin khỏi lịch sử xem")
                .build();
    }

    @DeleteMapping
    public ApiResponse<Void> clearMyHistory() {
        String userId = currentUserId();
        viewHistoryService.clearMyHistory(userId);
        return ApiResponse.<Void>builder()
                .message("Đã xóa toàn bộ lịch sử xem tin")
                .build();
    }

    private String currentUserId() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return context.userId();
    }
}
