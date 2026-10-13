package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.dto.billing.*;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClosureEvidenceAccessCheckerTest {
    ContractRepository contracts=mock(ContractRepository.class);
    ContractClosureRepository closures=mock(ContractClosureRepository.class);
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    ClosureEvidenceAccessChecker checker=new ClosureEvidenceAccessChecker(contracts,closures,mapper);
    ContractClosure closure=ContractClosure.builder().contractId("root").build();
    StorageObject file=StorageObject.builder().id("proof").ownerId("owner").referenceId("root")
        .referenceType("CONTRACT_CLOSURE_EVIDENCE").purpose(StoragePurpose.GENERAL).visibility(StorageVisibility.PRIVATE).build();
    @BeforeEach void setup() {
        when(contracts.findById("root")).thenReturn(Optional.of(Contract.builder().id("root").landlordId("owner").tenantId("tenant").build()));
        when(closures.findById("root")).thenReturn(Optional.of(closure));
    }
    void history(boolean dispute) throws Exception {
        closure.setHistorySnapshot(mapper.writeValueAsString(List.of(new ClosureEvent(dispute?"DISPUTED":"PROPOSED",1,Instant.now(),"owner","note",
            dispute?List.of():List.of(new ClosureDeduction("Hỏng kính",BigDecimal.TEN,List.of("proof"))),BigDecimal.TEN,BigDecimal.ZERO,dispute?List.of("proof"):List.of()))));
    }
    @Test void onlyPartiesReadActuallyAttachedPrivateEvidence() throws Exception {
        assertFalse(checker.canAccess("tenant",file));history(false);
        assertTrue(checker.canAccess("tenant",file));assertTrue(checker.canAccess("owner",file));
        assertFalse(checker.canAccess("stranger",file));assertFalse(checker.canAccess(null,file));
        file.setId("unattached");assertFalse(checker.canAccess("tenant",file));
        file.setId("proof");file.setReferenceId("another");assertFalse(checker.canAccess("tenant",file));
    }
    @Test void bothProposalAndObjectionEvidenceAreRetainedButUnattachedFilesAreNot() throws Exception {
        assertFalse(checker.preventsDeletion(file));history(false);assertTrue(checker.preventsDeletion(file));
        history(true);assertTrue(checker.canAccess("owner",file));assertTrue(checker.preventsDeletion(file));
        file.setId("unused");assertFalse(checker.preventsDeletion(file));
        file.setReferenceType("PAYMENT_REQUEST");assertFalse(checker.preventsDeletion(file));assertFalse(checker.canAccess("tenant",file));
    }
    @Test void malformedHistoryDeniesReadingAndPreventsDeletion() {
        closure.setHistorySnapshot("broken json");assertFalse(checker.canAccess("tenant",file));assertTrue(checker.preventsDeletion(file));
    }
}
