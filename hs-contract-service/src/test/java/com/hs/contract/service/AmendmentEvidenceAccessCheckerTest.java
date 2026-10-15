package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AmendmentEvidenceAccessCheckerTest {
    @Test void evidenceIsSharedOnlyAfterAttachmentAndHistoricalEvidenceCannotBeDeleted() {
        var contracts = mock(ContractRepository.class); var amendments = mock(ContractAmendmentRepository.class);
        var checker = new AmendmentEvidenceAccessChecker(contracts,amendments,new ObjectMapper());
        var f = StorageObject.builder().id("file").referenceId("root").referenceType("CONTRACT_AMENDMENT_EVIDENCE")
                .purpose(StoragePurpose.GENERAL).visibility(StorageVisibility.PRIVATE).build();
        when(contracts.findById("root")).thenReturn(Optional.of(Contract.builder().landlordId("owner").tenantId("tenant").build()));
        when(amendments.findByContractIdOrderByCreatedAtDesc("root")).thenReturn(List.of());
        assertFalse(checker.canAccess("tenant",f)); assertFalse(checker.preventsDeletion(f));
        when(amendments.findByContractIdOrderByCreatedAtDesc("root")).thenReturn(List.of(ContractAmendment.builder().status("REJECTED").evidenceSnapshot("[\"file\"]").build()));
        assertTrue(checker.canAccess("tenant",f)); assertTrue(checker.canAccess("owner",f)); assertFalse(checker.canAccess("outsider",f)); assertTrue(checker.preventsDeletion(f));
        f.setReferenceId("different-root"); assertFalse(checker.canAccess("tenant",f));
    }
}
