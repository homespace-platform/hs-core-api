package com.hs.contract.service;

import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.ContractRepository;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.service.StorageAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContractStorageAccessChecker implements StorageAccessChecker {

    private static final String CONTRACT_REFERENCE_TYPE = "CONTRACT";

    private final ContractRepository contractRepository;

    @Override
    public boolean canAccess(String userId, StorageObject object) {
        if (userId == null
                || object.getPurpose() != StoragePurpose.CONTRACT_DOCUMENT
                || !CONTRACT_REFERENCE_TYPE.equals(object.getReferenceType())
                || object.getReferenceId() == null) {
            return false;
        }

        return contractRepository.findById(object.getReferenceId())
                .map(contract -> userId.equals(contract.getLandlordId())
                        || (userId.equals(contract.getTenantId())
                        && contract.getStatus() != ContractStatus.DRAFT))
                .orElse(false);
    }
}
