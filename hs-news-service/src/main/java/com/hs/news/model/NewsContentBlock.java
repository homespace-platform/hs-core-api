package com.hs.news.model;

import com.hs.news.model.constant.NewsBlockType;

public record NewsContentBlock(
        NewsBlockType type,
        String text,
        String storageObjectId,
        String altText,
        String caption
) {
}
