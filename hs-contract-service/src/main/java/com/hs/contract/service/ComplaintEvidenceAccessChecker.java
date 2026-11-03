package com.hs.contract.service;
import com.hs.payment.repository.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.service.StorageAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Only files captured in a real case/event are shared, never arbitrary private uploads for that rental. */
@Component @RequiredArgsConstructor
public class ComplaintEvidenceAccessChecker implements StorageAccessChecker {
    private final RentalRequestRepository rentals;
    private final DisputeCaseRepository cases;
    private final DisputeCaseEventRepository events;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Override public boolean canAccess(String actor,StorageObject f){
        if(actor==null||!matches(f))return false;
        var r=rentals.findById(f.getReferenceId()).orElse(null);
        try{return r!=null&&(actor.equals(r.getRenterId())||actor.equals(r.getOwnerId()))&&attached(f);}catch(RuntimeException e){return false;}
    }
    @Override public boolean preventsDeletion(StorageObject f){if(!matches(f))return false;try{return attached(f);}catch(RuntimeException e){return true;}}
    private boolean matches(StorageObject f){return f.getPurpose()==StoragePurpose.GENERAL&&f.getVisibility()==StorageVisibility.PRIVATE&&"RENTAL_COMPLAINT_EVIDENCE".equals(f.getReferenceType())&&f.getReferenceId()!=null;}
    private boolean attached(StorageObject f){
        return cases.findBySubjectTypeAndRentalRequestIdOrderByOpenedAtDesc("RENTAL",f.getReferenceId()).stream()
            .anyMatch(c->ComplaintEvidenceSnapshot.ids(mapper,c,events.findByCaseIdOrderByRevisionAsc(c.getId())).contains(f.getId()));
    }
}
