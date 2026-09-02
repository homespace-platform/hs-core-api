package com.hs.news.dto.request;

import com.hs.news.model.constant.NewsBlockType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NewsContentBlockRequest(
        @NotNull NewsBlockType type,
        @Size(max = 20000) String text,
        @Size(max = 36) String storageObjectId,
        @Size(max = 500) String altText,
        @Size(max = 1000) String caption
) {
}
