package com.hs.api.facade;

import com.hs.api.dto.OnboardingStatusResponse;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.payment.service.BankAccountService;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.service.AddressService;
import com.hs.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingFacade {

    private final UserService userService;
    private final AddressService addressService;
    private final BankAccountService bankAccountService;

    public OnboardingStatusResponse getOnboardingStatus() {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null || ctx.userId() == null) {
            return OnboardingStatusResponse.builder()
                    .completed(false)
                    .version(2)
                    .requiredSteps(List.of("PROFILE", "ADDRESS", "PASSWORD", "BANK_ACCOUNT"))
                    .completedSteps(List.of())
                    .nextStep("PROFILE")
                    .build();
        }

        String userId = ctx.userId();
        UserProfileResponse profile = userService.getUserProfile();

        boolean profileComplete = hasText(profile.firstName()) && hasText(profile.lastName())
                && hasText(profile.phone()) && profile.dob() != null && profile.gender() != null;

        boolean addressComplete = profile.address() != null && hasText(profile.address().streetLine());
        boolean passwordComplete = userService.hasPassword();
        boolean bankComplete = bankAccountService.hasActiveBankAccount(userId);

        List<String> completedSteps = new ArrayList<>();
        if (profileComplete) completedSteps.add("PROFILE");
        if (addressComplete) completedSteps.add("ADDRESS");
        if (passwordComplete) completedSteps.add("PASSWORD");
        if (bankComplete) completedSteps.add("BANK_ACCOUNT");

        List<String> requiredSteps = List.of("PROFILE", "ADDRESS", "PASSWORD", "BANK_ACCOUNT");

        String nextStep;
        if (!profileComplete) {
            nextStep = "PROFILE";
        } else if (!addressComplete) {
            nextStep = "ADDRESS";
        } else if (!passwordComplete) {
            nextStep = "PASSWORD";
        } else if (!bankComplete) {
            nextStep = "BANK_ACCOUNT";
        } else {
            nextStep = "COMPLETED";
        }

        boolean allDone = profileComplete && addressComplete && passwordComplete && bankComplete;

        if (allDone && (!Boolean.TRUE.equals(profile.onBoarded()) || profile.onboardingVersion() == null || profile.onboardingVersion() < 2)) {
            userService.completeOnboarding(userId);
        }

        return OnboardingStatusResponse.builder()
                .completed(allDone)
                .version(2)
                .requiredSteps(requiredSteps)
                .completedSteps(completedSteps)
                .nextStep(nextStep)
                .build();
    }

    public void completeOnboardingIfEligible(String userId) {
        if (bankAccountService.hasActiveBankAccount(userId)) {
            userService.completeOnboarding(userId);
        }
    }

    private boolean hasText(String str) {
        return str != null && !str.isBlank();
    }
}
