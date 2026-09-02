package com.hs.api.controller.publish;

import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.news.dto.response.NewsResponse;
import com.hs.news.dto.response.NewsSummaryResponse;
import com.hs.news.model.constant.NewsCategory;
import com.hs.news.service.NewsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/public/news")
@RequiredArgsConstructor
@Validated
public class PublicNewsController {
    private final NewsService newsService;

    @GetMapping
    public PageResponse<NewsSummaryResponse> findPublished(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size,
            @RequestParam(required = false) NewsCategory category,
            @RequestParam(required = false) String keyword) {
        return newsService.findPublished(page, size, category, keyword);
    }

    @GetMapping("/{slug}")
    public ApiResponse<NewsResponse> getBySlug(@PathVariable String slug) {
        return ApiResponse.<NewsResponse>builder()
                .result(newsService.getPublishedBySlug(slug))
                .build();
    }
}
