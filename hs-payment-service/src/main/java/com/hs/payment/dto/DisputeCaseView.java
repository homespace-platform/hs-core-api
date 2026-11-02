package com.hs.payment.dto;
import com.hs.payment.model.constant.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
public record DisputeCaseView(String id,String paymentId,String rentalRequestId,String contractId,
        String payerId,String payeeId,String openedBy,String reason,Instant openedAt,
        DisputeCaseStatus status,PaymentStatus previousPaymentStatus,PaymentStatus paymentStatus,
        PaymentType paymentType,PaymentDirection direction,BigDecimal amount,String currency,
        long revision,String proposedBy,DisputeResolution proposedResolution,String proposalReason,
        String assignedTo,String resolvedBy,DisputeResolution resolution,String resolutionReason,Instant resolvedAt,
        List<String> allowedActions,List<Event> events,List<Evidence> evidences) {
    public record Event(long revision,String action,String actorId,String actorRole,String reason,
            String evidenceStorageId,String metadataJson,Instant occurredAt) {}
    public record Evidence(String storageId,String uploadedBy,String note,Instant createdAt) {}
}
