package com.hs.payment.dto;

import com.hs.payment.model.constant.UploadSessionStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record UploadSessionStatusResponse(
        String sessionId,
        UploadSessionStatus status,
        Instant expiresAt,
        UploadSessionEvidenceResponse evidence
) {}
