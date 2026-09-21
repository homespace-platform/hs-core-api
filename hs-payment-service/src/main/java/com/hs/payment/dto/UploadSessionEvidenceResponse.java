package com.hs.payment.dto;

import lombok.Builder;

@Builder
public record UploadSessionEvidenceResponse(
        String storageId,
        String originalFileName,
        String contentType,
        Long fileSize,
        String previewUrl
) {}
