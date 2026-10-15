package com.hs.contract.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.repository.*;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.service.StorageAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component @RequiredArgsConstructor
public class AmendmentEvidenceAccessChecker implements StorageAccessChecker {
    private final ContractRepository contracts;
    private final ContractAmendmentRepository amendments;
    private final ObjectMapper mapper;
    private boolean belongs(StorageObject f) { return f.getPurpose() == StoragePurpose.GENERAL && f.getVisibility() == StorageVisibility.PRIVATE && "CONTRACT_AMENDMENT_EVIDENCE".equals(f.getReferenceType()) && f.getReferenceId() != null; }
    @Override public boolean canAccess(String actor, StorageObject f) {
        if (actor == null || !belongs(f)) return false;
        var c = contracts.findById(f.getReferenceId()).orElse(null);
        if (c == null || !actor.equals(c.getLandlordId()) && !actor.equals(c.getTenantId())) return false;
        try { return attached(f); } catch (Exception e) { return false; }
    }
    @Override public boolean preventsDeletion(StorageObject f) { if (!belongs(f)) return false; try { return attached(f); } catch (Exception e) { return true; } }
    private boolean attached(StorageObject f) throws Exception {
        for (var a : amendments.findByContractIdOrderByCreatedAtDesc(f.getReferenceId()))
            if (a.getEvidenceSnapshot() != null && mapper.readValue(a.getEvidenceSnapshot(), new TypeReference<List<String>>() {}).contains(f.getId())) return true;
        return false;
    }
}
