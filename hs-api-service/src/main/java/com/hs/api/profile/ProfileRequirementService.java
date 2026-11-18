package com.hs.api.profile;

import com.hs.common.context.UserContextHolder;
import com.hs.payment.service.BankAccountService;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.service.UserService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Single source for the four service requirements (password is separate: web enforces it, Keycloak owns it). */
@Service
@RequiredArgsConstructor
public class ProfileRequirementService {

    private final UserService userService;
    private final BankAccountService bankAccountService;

    public record Requirements(boolean profile, boolean address, boolean bankAccount, boolean kyc,
                               UserProfileResponse user) {
        /** Missing codes in the fixed order PROFILE, ADDRESS, BANK_ACCOUNT, KYC. */
        public List<String> missing() {
            List<String> missing = new ArrayList<>();
            if (!profile) missing.add("PROFILE");
            if (!address) missing.add("ADDRESS");
            if (!bankAccount) missing.add("BANK_ACCOUNT");
            if (!kyc) missing.add("KYC");
            return missing;
        }
    }

    /** Evaluates the current authenticated user. */
    public Requirements evaluate() {
        UserProfileResponse p = userService.getUserProfile();
        boolean profile = hasText(p.firstName()) && hasText(p.lastName())
                && hasText(p.phone());
        boolean address = p.address() != null && hasText(p.address().refId())
                && (hasText(p.address().street()) || hasText(p.address().name()))
                && hasText(p.address().cityId()) && hasText(p.address().wardId())
                && p.address().latitude() != null && p.address().longitude() != null;
        boolean bank = bankAccountService.hasActiveBankAccount(UserContextHolder.get().userId());
        // Same KYC source as KycGateService: a VERIFIED record from an accepted provider.
        return new Requirements(profile, address, bank, Boolean.TRUE.equals(p.kycVerified()), p);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
