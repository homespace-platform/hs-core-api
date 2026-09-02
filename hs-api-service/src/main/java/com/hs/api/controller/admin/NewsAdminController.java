package com.hs.api.controller.admin;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.news.dto.request.NewsUpsertRequest;
import com.hs.news.dto.response.NewsResponse;
import com.hs.news.dto.response.NewsSummaryResponse;
import com.hs.news.model.constant.NewsCategory;
import com.hs.news.model.constant.NewsStatus;
import com.hs.news.service.NewsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/news")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAuthority('ADMIN')")
public class NewsAdminController {
    private final NewsService newsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NewsResponse> create(@RequestBody @Valid NewsUpsertRequest request) {
        return ApiResponse.<NewsResponse>builder()
                .message("News article created")
                .result(newsService.create(currentUserId(), request))
                .build();
    }

    @GetMapping
    public PageResponse<NewsSummaryResponse> findAll(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) NewsStatus status,
            @RequestParam(required = false) NewsCategory category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return newsService.findAdmin(page, size, status, category, keyword, sort);
    }

    @GetMapping("/{newsId}")
    public ApiResponse<NewsResponse> getById(@PathVariable String newsId) {
        return ApiResponse.<NewsResponse>builder().result(newsService.getAdmin(newsId)).build();
    }

    @PutMapping("/{newsId}")
    public ApiResponse<NewsResponse> update(
            @PathVariable String newsId,
            @RequestBody @Valid NewsUpsertRequest request) {
        return ApiResponse.<NewsResponse>builder()
                .message("News article updated")
                .result(newsService.update(currentUserId(), newsId, request))
                .build();
    }

    @DeleteMapping("/{newsId}")
    public ApiResponse<Void> delete(@PathVariable String newsId) {
        newsService.delete(newsId);
        return ApiResponse.<Void>builder().message("News article deleted").build();
    }

    private String currentUserId() {
        UserContext context = UserContextHolder.get();
        return context == null ? null : context.userId();
    }
}
