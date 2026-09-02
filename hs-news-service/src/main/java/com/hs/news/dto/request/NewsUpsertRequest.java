package com.hs.news.dto.request;

import com.hs.news.model.constant.NewsCategory;
import com.hs.news.model.constant.NewsStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record NewsUpsertRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 255) String slug,
        @NotBlank @Size(max = 1000) String summary,
        @NotNull NewsCategory category,
        @NotNull NewsStatus status,
        Boolean featured,
        @Size(max = 20) List<@NotBlank @Size(max = 50) String> tags,
        @Size(max = 36) String thumbnailStorageObjectId,
        @NotNull @Size(max = 200) List<@Valid NewsContentBlockRequest> contentBlocks
) {
}
