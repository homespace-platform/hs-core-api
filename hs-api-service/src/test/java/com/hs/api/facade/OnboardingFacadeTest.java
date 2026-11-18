package com.hs.api.facade;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.payment.service.BankAccountService;
import com.hs.user.dto.response.AddressResponse;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.service.UserService;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnboardingFacadeTest {
    private final UserService users=mock(UserService.class);
    private final BankAccountService banks=mock(BankAccountService.class);
    private final OnboardingFacade facade=new OnboardingFacade(users,banks,new com.hs.api.profile.ProfileRequirementService(users,banks));

    @AfterEach void clearContext() { UserContextHolder.clear(); }

    private void profile(AddressResponse address) {
        UserContextHolder.set(new UserContext("customer-1","customer@example.test"));
        when(users.getUserProfile()).thenReturn(UserProfileResponse.builder().address(address).build());
    }

    @Test void concreteNamedPlaceCompletesAddressStepEvenWhenStreetIsNotParsed() {
        profile(AddressResponse.builder().refId("vm:concrete").street("").name("194 Đình Phong Phú")
                .cityId("12").wardId("4620").latitude(new BigDecimal("10.8344664"))
                .longitude(new BigDecimal("106.7825491")).build());
        assertTrue(facade.getOnboardingStatus().completedSteps().contains("ADDRESS"));
    }

    @Test void typedTextWithoutVerifiedCoordinatesDoesNotCompleteAddressStep() {
        profile(AddressResponse.builder().refId("vm:concrete").street("").name("194 Đình Phong Phú")
                .cityId("12").wardId("4620").build());
        assertFalse(facade.getOnboardingStatus().completedSteps().contains("ADDRESS"));
    }

    @Test void resultWithoutStreetOrSpecificNameDoesNotCompleteAddressStep() {
        profile(AddressResponse.builder().refId("vm:area").street("").name("")
                .cityId("12").wardId("4620").latitude(BigDecimal.TEN).longitude(BigDecimal.valueOf(106)).build());
        assertFalse(facade.getOnboardingStatus().completedSteps().contains("ADDRESS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "USER"})
    void everyRoleMustCompletePasswordAndBankSteps(String role) {
        UserContextHolder.set(new UserContext("actor", "actor@example.test"));
        var address = AddressResponse.builder().refId("verified-place").street("Street")
                .cityId("12").wardId("4620").latitude(BigDecimal.TEN).longitude(BigDecimal.valueOf(106)).build();
        when(users.getUserProfile()).thenReturn(UserProfileResponse.builder().role(role).onBoarded(false)
                .firstName("A").lastName("B").phone("0900000000")
                .address(address).build());

        var status = facade.getOnboardingStatus();
        assertEquals("PASSWORD", status.nextStep());
        assertFalse(status.passwordSet());
        assertEquals(java.util.List.of("BANK_ACCOUNT", "KYC"), status.missingRequirements());
        assertFalse(status.serviceReady());
        verify(users, never()).completeOnboarding(anyString());
        when(users.hasPassword()).thenReturn(true);
        assertEquals("BANK_ACCOUNT", facade.getOnboardingStatus().nextStep());
        verify(users, never()).completeOnboarding(anyString());

        when(banks.hasActiveBankAccount("actor")).thenReturn(true);
        var done = facade.getOnboardingStatus();
        assertTrue(done.completed());
        assertTrue(done.passwordSet());
        // completed stays the 4-step onboarding flag (no KYC); serviceReady additionally needs KYC.
        assertEquals(java.util.List.of("KYC"), done.missingRequirements());
        assertFalse(done.serviceReady());
        verify(users).completeOnboarding("actor");
    }
}
