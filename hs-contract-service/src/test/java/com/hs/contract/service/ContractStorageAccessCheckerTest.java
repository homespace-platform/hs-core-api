package com.hs.contract.service;

import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.ContractRepository;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractStorageAccessCheckerTest {

    @Test
    void tenantCanAccessDocumentAfterContractIsSent() {
        ContractRepository repository = mock(ContractRepository.class);
        ContractStorageAccessChecker checker = new ContractStorageAccessChecker(repository);
        Contract contract = Contract.builder()
                .id("contract-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.PENDING_REVIEW)
                .build();
        StorageObject object = contractDocument("contract-1");
        when(repository.findById("contract-1")).thenReturn(Optional.of(contract));

        assertTrue(checker.canAccess("tenant-1", object));
    }

    @Test
    void tenantCannotAccessDocumentWhileContractIsDraft() {
        ContractRepository repository = mock(ContractRepository.class);
        ContractStorageAccessChecker checker = new ContractStorageAccessChecker(repository);
        Contract contract = Contract.builder()
                .id("contract-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.DRAFT)
                .build();
        StorageObject object = contractDocument("contract-1");
        when(repository.findById("contract-1")).thenReturn(Optional.of(contract));

        assertFalse(checker.canAccess("tenant-1", object));
    }

    @Test
    void unrelatedUserCannotAccessContractDocument() {
        ContractRepository repository = mock(ContractRepository.class);
        ContractStorageAccessChecker checker = new ContractStorageAccessChecker(repository);
        Contract contract = Contract.builder()
                .id("contract-1")
                .landlordId("landlord-1")
                .tenantId("tenant-1")
                .status(ContractStatus.PENDING_REVIEW)
                .build();
        StorageObject object = contractDocument("contract-1");
        when(repository.findById("contract-1")).thenReturn(Optional.of(contract));

        assertFalse(checker.canAccess("other-user", object));
    }

    private StorageObject contractDocument(String contractId) {
        return StorageObject.builder()
                .purpose(StoragePurpose.CONTRACT_DOCUMENT)
                .referenceType("CONTRACT")
                .referenceId(contractId)
                .build();
    }
}
