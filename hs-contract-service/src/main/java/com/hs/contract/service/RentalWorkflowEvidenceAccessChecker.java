package com.hs.contract.service;
import com.hs.contract.repository.RentalWorkflowRecordRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.service.StorageAccessChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hs.contract.dto.billing.RentalWorkflowView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component @RequiredArgsConstructor
public class RentalWorkflowEvidenceAccessChecker implements StorageAccessChecker {
    private final RentalWorkflowRecordRepository records;
    private final RentalRequestRepository rentals;
    private final ObjectMapper mapper;
    private boolean matches(StorageObject f){return f.getPurpose()==StoragePurpose.GENERAL&&f.getVisibility()==StorageVisibility.PRIVATE&&"RENTAL_COMPLAINT_EVIDENCE".equals(f.getReferenceType())&&f.getReferenceId()!=null;}
    private boolean attached(StorageObject f){return records.findByRentalRequestId(f.getReferenceId()).stream().anyMatch(w->{try{return mapper.readValue(w.getHistoryJson()==null?"[]":w.getHistoryJson(),new TypeReference<List<RentalWorkflowView.Event>>(){}).stream().anyMatch(e->e.evidenceIds()!=null&&e.evidenceIds().contains(f.getId()));}catch(Exception e){throw new IllegalStateException("Cannot verify retained workflow evidence",e);}});}
    @Override public boolean canAccess(String actor,StorageObject f){if(actor==null||!matches(f))return false;var r=rentals.findById(f.getReferenceId()).orElse(null);try{return r!=null&&(actor.equals(r.getOwnerId())||actor.equals(r.getRenterId()))&&attached(f);}catch(RuntimeException e){return false;}}
    @Override public boolean preventsDeletion(StorageObject f){if(!matches(f))return false;try{return attached(f);}catch(RuntimeException e){return true;}}
}
