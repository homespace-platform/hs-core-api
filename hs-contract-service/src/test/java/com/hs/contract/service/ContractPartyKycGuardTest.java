package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.response.KycVerifiedDetailsResponse;
import com.hs.user.service.KycService;
import com.hs.user.service.kyc.KycGateService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractPartyKycGuardTest {
    private final KycGateService gate = mock(KycGateService.class);
    private final KycService kyc = mock(KycService.class);
    private final ContractPartyKycGuard guard = new ContractPartyKycGuard(gate, kyc);

    private static KycVerifiedDetailsResponse details(String fullName, String cccd, String birthDate, String gender) {
        return new KycVerifiedDetailsResponse(cccd, fullName, birthDate, "Địa chỉ", gender, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private void verified(String userId) {
        doNothing().when(gate).requireVerifiedIdentity(userId);
        when(kyc.getVerifiedDetails(userId)).thenReturn(details("Nguyen Van A", "079090001234", "16/02/1990", "Nam"));
    }

    private void expired(String userId) {
        doThrow(new AppException(UserErrorCode.KYC_REQUIRED)).when(gate).requireVerifiedIdentity(userId);
    }

    private int codeOf(String landlord, String tenant, String actor) {
        return assertThrows(AppException.class, () -> guard.require(landlord, tenant, actor)).getCode();
    }

    @Test
    void bothPartiesVerifiedPasses() {
        verified("landlord");
        verified("tenant");
        assertDoesNotThrow(() -> guard.require("landlord", "tenant", "landlord"));
        assertDoesNotThrow(() -> guard.require("landlord", "tenant", "tenant"));
        assertDoesNotThrow(() -> guard.require("landlord", "tenant", null));
    }

    @Test
    void counterpartyWithoutKycGetsGenericCodeNotTheCallersKycCode() {
        verified("landlord");
        expired("tenant");
        assertEquals(UserErrorCode.COUNTERPARTY_KYC_REQUIRED.getCode(), codeOf("landlord", "tenant", "landlord"));
        assertEquals(UserErrorCode.COUNTERPARTY_KYC_REQUIRED.getCode(), codeOf("landlord", "tenant", null));
    }

    @Test
    void callerWithoutKycStillGetsKycRequiredEvenWhenCounterpartyIsAlsoMissing() {
        expired("landlord");
        expired("tenant");
        assertEquals(UserErrorCode.KYC_REQUIRED.getCode(), codeOf("landlord", "tenant", "landlord"));
        assertEquals(UserErrorCode.KYC_REQUIRED.getCode(), codeOf("landlord", "tenant", "tenant"));
    }

    @Test
    void verifiedStatusWithoutPrintableIdentityIsNotEnough() {
        verified("landlord");
        doNothing().when(gate).requireVerifiedIdentity("tenant");
        for (var incomplete : new KycVerifiedDetailsResponse[] {null, details("", "079", "16/02/1990", "Nam"),
                details("A", null, "16/02/1990", "Nam"), details("A", "079", " ", "Nam"), details("A", "079", "16/02/1990", null)}) {
            when(kyc.getVerifiedDetails("tenant")).thenReturn(incomplete);
            assertEquals(UserErrorCode.COUNTERPARTY_KYC_REQUIRED.getCode(), codeOf("landlord", "tenant", "landlord"));
        }
    }

    @Test
    void missingPartyIdIsRejected() {
        verified("landlord");
        assertEquals(UserErrorCode.COUNTERPARTY_KYC_REQUIRED.getCode(), codeOf("landlord", null, "landlord"));
    }
}
