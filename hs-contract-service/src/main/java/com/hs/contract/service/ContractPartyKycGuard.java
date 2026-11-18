package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.service.KycService;
import com.hs.user.service.kyc.KycGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Contract party identity is printed only from verified eKYC, and KYC can lapse after a request was created.
 * Every step that renders or sends party identity must therefore re-check both parties.
 */
@Component
@RequiredArgsConstructor
public class ContractPartyKycGuard {
    private final KycGateService gate;
    private final KycService kyc;

    /** The actor gets KYC_REQUIRED; the other party gets a generic error that reveals nothing about their KYC. */
    public void require(String landlordId, String tenantId, String actorId) {
        // Actor first so the caller is always told about their own KYC before the counterparty's.
        boolean actorIsTenant = actorId != null && actorId.equals(tenantId);
        String first = actorIsTenant ? tenantId : landlordId;
        String second = actorIsTenant ? landlordId : tenantId;
        check(first, actorId);
        check(second, actorId);
    }

    private void check(String userId, String actorId) {
        if (!verified(userId)) {
            throw new AppException(userId != null && userId.equals(actorId)
                    ? UserErrorCode.KYC_REQUIRED : UserErrorCode.COUNTERPARTY_KYC_REQUIRED);
        }
    }

    private boolean verified(String userId) {
        if (userId == null || userId.isBlank()) return false;
        try {
            gate.requireVerifiedIdentity(userId);
        } catch (AppException notVerified) {
            return false;
        }
        var details = kyc.getVerifiedDetails(userId);
        return details != null && has(details.fullName()) && has(details.cccd())
                && has(details.birthDate()) && has(details.gender());
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }
}
