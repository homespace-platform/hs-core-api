package com.hs.contract.dto.complaint;

import com.hs.contract.dto.billing.RentalWorkflowView;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable server-captured context, never accepted from a complaint request body. */
public record ComplaintWorkflowSource(String workflowId,String kind,long revision,String contractId,
    String sourceRevisionId,String status,String proposedBy,Instant ownerConfirmedAt,Instant tenantConfirmedAt,
    Instant capturedAt,Map<String,Object> proposal,List<RentalWorkflowView.Event> history,List<String> evidenceIds,
    Map<String,Object> rentCalculation,String contentHash,List<Document> documents) {
    public record Document(String storageObjectId,String signingContractId,String purpose,long sourceRevision,Instant signedAt) {}
}
