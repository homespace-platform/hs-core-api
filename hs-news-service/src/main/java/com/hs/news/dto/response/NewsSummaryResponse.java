package com.hs.news.dto.response;

import com.hs.news.model.constant.NewsCategory;
import com.hs.news.model.constant.NewsStatus;

import java.time.Instant;
import java.util.List;

public record NewsSummaryResponse(
        String id,
        String title,
        String slug,
        String summary,
        NewsCategory category,
        NewsStatus status,
        boolean featured,
        List<String> tags,
        String thumbnailUrl,
        String authorName,
        Instant publishedAt,
        Instant createdAt
) {
}
