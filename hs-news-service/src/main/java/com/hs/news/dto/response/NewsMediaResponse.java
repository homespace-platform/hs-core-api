package com.hs.news.dto.response;

import com.hs.news.model.constant.NewsMediaRole;

public record NewsMediaResponse(
        String id,
        String storageObjectId,
        NewsMediaRole role,
        int sortOrder,
        String altText,
        String caption,
        String url
) {
}
