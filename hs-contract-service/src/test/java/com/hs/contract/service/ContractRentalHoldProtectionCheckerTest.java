package com.hs.contract.service;

import com.hs.contract.repository.ContractRepository;
import com.hs.payment.repository.DisputeCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractRentalHoldProtectionCheckerTest {
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final DisputeCaseRepository disputes = mock(DisputeCaseRepository.class);
    private final ContractRentalHoldProtectionChecker checker = new ContractRentalHoldProtectionChecker(contracts);

    @Test void openComplaintProtectsTheHoldEvenWithoutAContract() {
        ReflectionTestUtils.setField(checker, "disputeCases", disputes);
        when(contracts.existsByRentalRequestIdAndStatusIn(any(), any())).thenReturn(false);
        when(disputes.existsByRentalRequestIdAndActiveKeyIsNotNull("rental")).thenReturn(true);
        assertTrue(checker.isProtected("rental"));
    }

    @Test void noContractAndNoComplaintLeavesTheHoldExpirable() {
        ReflectionTestUtils.setField(checker, "disputeCases", disputes);
        when(contracts.existsByRentalRequestIdAndStatusIn(any(), any())).thenReturn(false);
        assertFalse(checker.isProtected("rental"));
        assertFalse(checker.isProtected(null));
    }

    @Test void worksWithoutTheDisputeRepository() {
        when(contracts.existsByRentalRequestIdAndStatusIn(any(), any())).thenReturn(false);
        assertFalse(checker.isProtected("rental"));
    }
}
