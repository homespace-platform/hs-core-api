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
    private final MutualTerminationService termination;
    private final com.hs.contract.notification.AgreementSigningNotificationWriter signingNotices;
    private final com.hs.contract.repository.ContractRepository contracts;
    @EventListener @Transactional(propagation=Propagation.MANDATORY)
    public void signed(WorkflowDocumentSigned event){
        var w=support.records.findById(event.workflowId()).orElseThrow(support::missing);if(!event.contentHash().equals(WorkflowDocumentService.hash(w)))throw support.invalid("Văn bản ký không còn khớp hồ sơ nguồn.");
        var rental=support.rentals.findById(w.getRentalRequestId()).orElseThrow(support::missing);
        if("EARLY_EXIT".equals(w.getKind())){
            if("PROPOSED".equals(w.getStatus())){
                String consenting=w.getProposedBy().equals(rental.getOwnerId())?rental.getRenterId():rental.getOwnerId();termination.actSigned(rental.getId(),consenting,action(w.getRevision()));
            }
            signingNotices.fullySigned(contracts.findById(event.signingContractId()).orElseThrow(support::missing));
        }
    }
    private RentalWorkflowPayloads.Action action(long revision){return new RentalWorkflowPayloads.Action(revision,"Đã kiểm chứng đủ hai chữ ký SmartCA trên đúng PDF nguồn",true,false);}
}
