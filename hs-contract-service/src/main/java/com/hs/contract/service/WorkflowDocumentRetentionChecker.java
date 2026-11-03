package com.hs.contract.service;
import com.hs.contract.repository.WorkflowSigningDocumentRepository;
import com.hs.storage.service.StorageAccessChecker;
import com.hs.storage.model.StorageObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class WorkflowDocumentRetentionChecker implements StorageAccessChecker {
    private final WorkflowSigningDocumentRepository records;
    @Override public boolean canAccess(String actor,StorageObject object){return false;}
    @Override public boolean preventsDeletion(StorageObject object){return "CONTRACT".equals(object.getReferenceType())&&object.getReferenceId()!=null&&records.findBySigningContractId(object.getReferenceId()).isPresent();}
}
