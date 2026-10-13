package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.dto.billing.ClosureEvent;
import com.hs.contract.repository.ContractClosureRepository;
import com.hs.contract.repository.ContractRepository;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.service.StorageAccessChecker;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Only evidence attached to an actual proposal/objection is shared with the other party. */
@Component @RequiredArgsConstructor
public class ClosureEvidenceAccessChecker implements StorageAccessChecker {
    private final ContractRepository contracts;
    private final ContractClosureRepository closures;
    private final ObjectMapper mapper;
    @Override public boolean canAccess(String actor,StorageObject file) {
        if(actor==null || file.getPurpose()!=StoragePurpose.GENERAL || file.getVisibility()!=StorageVisibility.PRIVATE
                || !"CONTRACT_CLOSURE_EVIDENCE".equals(file.getReferenceType()) || file.getReferenceId()==null) return false;
        var contract=contracts.findById(file.getReferenceId()).orElse(null);
        if(contract==null || !actor.equals(contract.getTenantId()) && !actor.equals(contract.getLandlordId())) return false;
        try { return attached(file); } catch(Exception e) { return false; }
    }
    @Override public boolean preventsDeletion(StorageObject file) {
        if(!"CONTRACT_CLOSURE_EVIDENCE".equals(file.getReferenceType()) || file.getReferenceId()==null) return false;
        try { return attached(file); } catch(Exception e) { return true; }
    }
    private boolean attached(StorageObject file) throws Exception {
        var close=closures.findById(file.getReferenceId()).orElse(null);
        if(close==null || close.getHistorySnapshot()==null) return false;
            List<ClosureEvent> history=mapper.readValue(close.getHistorySnapshot(),new TypeReference<>(){});
            return history.stream().anyMatch(event->event.evidenceIds()!=null && event.evidenceIds().contains(file.getId())
                || event.deductions()!=null && event.deductions().stream().anyMatch(row->row.evidenceIds()!=null && row.evidenceIds().contains(file.getId())));
    }
}
