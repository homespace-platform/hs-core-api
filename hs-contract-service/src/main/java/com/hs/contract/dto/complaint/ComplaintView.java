package com.hs.contract.dto.complaint;
import com.hs.payment.dto.DisputeCaseView;
import com.hs.payment.model.constant.DisputeCaseStatus;
import java.time.Instant;
import java.util.List;
public record ComplaintView(String id,String rentalRequestId,String contractId,String tenantId,String landlordId,
    ComplaintCategory category,String reason,String openedBy,Instant openedAt,DisputeCaseStatus status,String stage,
    long revision,Instant respondBy,String responseDueFor,boolean overdue,String assignedTo,String outcome,
    String decision,String proposedBy,String proposalReason,Long sourceVersion,
    boolean tenantCompleted,boolean landlordCompleted,List<String> allowedActions,
    List<DisputeCaseView.Event> events,List<String> evidenceIds,Settlement settlement,String closingReason,List<ComplaintWorkflowSource> sources) {
    public record Settlement(int version,String status,java.math.BigDecimal deduction,java.math.BigDecimal refund,
        java.math.BigDecimal balance,String note,boolean ownerApproved,boolean accepted) {}
}
