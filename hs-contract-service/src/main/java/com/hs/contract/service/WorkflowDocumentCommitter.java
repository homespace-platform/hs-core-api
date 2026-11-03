package com.hs.contract.service;
import com.hs.contract.dto.billing.RentalWorkflowPayloads;
import com.hs.contract.event.WorkflowDocumentSigned;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
@Component @RequiredArgsConstructor
public class WorkflowDocumentCommitter {
    private final RentalWorkflowSupport support;
    private final MoveInHandoverService handover;
    private final MutualTerminationService termination;
    @EventListener @Transactional(propagation=Propagation.MANDATORY)
    public void signed(WorkflowDocumentSigned event){
        var w=support.records.findById(event.workflowId()).orElseThrow(support::missing);if(!event.contentHash().equals(WorkflowDocumentService.hash(w)))throw support.invalid("Văn bản ký không còn khớp hồ sơ nguồn.");
        var rental=support.rentals.findById(w.getRentalRequestId()).orElseThrow(support::missing);
        if("MOVE_IN".equals(w.getKind())){
            if(w.getOwnerConfirmedAt()==null)handover.act(rental.getId(),rental.getOwnerId(),"CONFIRM",action(w.getRevision()));
            if(w.getTenantConfirmedAt()==null)handover.act(rental.getId(),rental.getRenterId(),"CONFIRM",action(w.getRevision()));
        }else if("EARLY_EXIT".equals(w.getKind())&&"PROPOSED".equals(w.getStatus())){
            String consenting=w.getProposedBy().equals(rental.getOwnerId())?rental.getRenterId():rental.getOwnerId();termination.act(rental.getId(),consenting,"ACCEPT",action(w.getRevision()));
        }
    }
    private RentalWorkflowPayloads.Action action(long revision){return new RentalWorkflowPayloads.Action(revision,"Đã kiểm chứng đủ hai chữ ký SmartCA trên đúng PDF nguồn",true,false);}
}
