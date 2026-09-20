package com.hs.payment.dto;

import com.hs.payment.model.constant.PaymentEventType;
import com.hs.payment.model.constant.PaymentStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record PaymentEventResponse(
        String id,
        String paymentRequestId,
        PaymentEventType eventType,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        String actorId,
        String actorRole,
        String reason,
        String metadataJson,
        Instant createdAt
) {}
