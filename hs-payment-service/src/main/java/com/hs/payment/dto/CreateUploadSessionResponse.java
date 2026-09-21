package com.hs.payment.dto;

import com.hs.payment.model.constant.UploadSessionStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record CreateUploadSessionResponse(
        String sessionId,
        String uploadPath,
        String uploadPageUrl,
        Instant expiresAt,
        UploadSessionStatus status
) {}
