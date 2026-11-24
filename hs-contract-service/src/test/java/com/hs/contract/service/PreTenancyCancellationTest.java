package com.hs.contract.service;

import com.hs.contract.deposit.DepositAgreementService;
import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.payment.model.DepositRecord;
import com.hs.payment.model.constant.DepositStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** How a finished pre-tenancy cancellation leaves the deposit agreement, for every signature state. */
class PreTenancyCancellationTest {
    final DepositAgreementService agreements = mock(DepositAgreementService.class);

    Contract dc(ContractStatus status, boolean signed) {
        return Contract.builder().status(status).signedAt(signed ? Instant.parse("2026-11-24T00:00:00Z") : null).build();
    }
    DepositRecord deposit(DepositStatus status) { return DepositRecord.builder().status(status).build(); }

    @Test void unsignedOrHalfSignedAgreementIsCancelledByTheCaller() {
        for (var status : new ContractStatus[]{ContractStatus.DRAFT, ContractStatus.LANDLORD_SIGNATURE_PENDING, ContractStatus.TENANT_SIGNATURE_PENDING}) {
            var c = dc(status, false);
            assertTrue(PreTenancyCancellation.endDepositAgreement(agreements, c, deposit(DepositStatus.HELD), "r"));
            assertEquals(ContractStatus.CANCELLED, c.getStatus());
        }
        verifyNoInteractions(agreements);
    }

    @Test void fullySignedWithForfeitedDepositIsTerminatedWithTheForfeitReason() {
        var c = dc(ContractStatus.ACTIVE, true);
        assertFalse(PreTenancyCancellation.endDepositAgreement(agreements, c, deposit(DepositStatus.RETAINED_BY_LANDLORD), "r"));
        verify(agreements).terminateForCancellation("r", PreTenancyCancellation.FORFEIT_REASON);
    }

    @Test void fullySignedWithRefundedDepositIsTerminatedToo() {
        var c = dc(ContractStatus.ACTIVE, true);
        assertFalse(PreTenancyCancellation.endDepositAgreement(agreements, c, deposit(DepositStatus.REFUND_DUE), "r"));
        verify(agreements).terminateForCancellation("r", PreTenancyCancellation.DEPOSIT_REFUNDED_END);
    }

    @Test void alreadyTerminatedOrMissingAgreementIsLeftAlone() {
        assertFalse(PreTenancyCancellation.endDepositAgreement(agreements, dc(ContractStatus.TERMINATED, true), null, "r"));
        assertFalse(PreTenancyCancellation.endDepositAgreement(agreements, null, null, "r"));
        verifyNoInteractions(agreements);
    }
}
