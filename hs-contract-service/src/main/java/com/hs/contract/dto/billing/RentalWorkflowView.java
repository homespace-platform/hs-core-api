package com.hs.contract.dto.billing;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import com.hs.payment.dto.PaymentRequestResponse;
public record RentalWorkflowView(String kind,String status,long revision,String role,String contractId,String sourceRevisionId,
    String proposedBy,Instant serverNow,Instant respondBy,Instant ownerConfirmedAt,Instant tenantConfirmedAt,
    Object proposal,List<Event> history,List<String> allowedActions,String blockedReason,String complaintId,
    String depositObligation,List<PaymentRequestResponse> refundPayments,FinalRentCalculation rentCalculation,TerminationOptions terminationOptions) {
    public RentalWorkflowView withRentCalculation(FinalRentCalculation calculation){return new RentalWorkflowView(kind,status,revision,role,contractId,sourceRevisionId,proposedBy,serverNow,respondBy,ownerConfirmedAt,tenantConfirmedAt,proposal,history,allowedActions,blockedReason,complaintId,depositObligation,refundPayments,calculation,terminationOptions);}
    public RentalWorkflowView withTerminationOptions(TerminationOptions options){return new RentalWorkflowView(kind,status,revision,role,contractId,sourceRevisionId,proposedBy,serverNow,respondBy,ownerConfirmedAt,tenantConfirmedAt,proposal,history,allowedActions,blockedReason,complaintId,depositObligation,refundPayments,rentCalculation,options);}
    public record TerminationOptions(LocalDate currentEndExclusive,LocalDate proposedLastOccupiedDay,List<Choice> choices) {}
    public record Choice(LocalDate lastOccupiedDay,LocalDate endExclusive) {}
    public record Event(long revision,String action,String actorId,String role,Instant at,String reason,
        Map<String,Object> proposal,List<String> evidenceIds) {}
}
