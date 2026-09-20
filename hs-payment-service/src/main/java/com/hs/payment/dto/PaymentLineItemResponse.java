package com.hs.payment.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record PaymentLineItemResponse(
        String id,
        String type,
        String displayName,
        BigDecimal amount,
        Integer quantity,
        BigDecimal unitPrice,
        String calculationDescription,
        String note,
        Integer sortOrder
) {}
