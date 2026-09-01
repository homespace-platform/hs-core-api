package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.response.PublicListingSummaryResponse;
import com.hs.listing.service.ListingFavoriteService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/favorites")
@RequiredArgsConstructor
@Validated
public class FavoriteController {

    private final ListingFavoriteService favoriteService;

    @PostMapping("/{listingId}/toggle")
    public ApiResponse<Map<String, Object>> toggleFavorite(@PathVariable String listingId) {
        String userId = currentUserId();
        boolean isFavorite = favoriteService.toggleFavorite(userId, listingId);
        return ApiResponse.<Map<String, Object>>builder()
                .message(isFavorite ? "Đã lưu vào danh sách yêu thích" : "Đã bỏ lưu tin đăng")
                .result(Map.of("listingId", listingId, "isFavorite", isFavorite))
                .build();
    }

    @DeleteMapping("/{listingId}")
    public ApiResponse<Void> removeFavorite(@PathVariable String listingId) {
        String userId = currentUserId();
        favoriteService.removeFavorite(userId, listingId);
        return ApiResponse.<Void>builder()
                .message("Đã bỏ lưu tin đăng")
                .build();
    }

    @GetMapping("/ids")
    public ApiResponse<List<String>> getFavoriteListingIds() {
        String userId = currentUserId();
        List<String> ids = favoriteService.getFavoriteListingIds(userId);
        return ApiResponse.<List<String>>builder()
                .result(ids)
                .build();
    }

    @GetMapping
    public PageResponse<PublicListingSummaryResponse> getMyFavorites(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        String userId = currentUserId();
        return favoriteService.getMyFavorites(userId, page, size);
    }

    private String currentUserId() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return context.userId();
    }
}
