package com.hs.api.facade;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.payment.service.BankAccountService;
import com.hs.user.dto.response.AddressResponse;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.service.AddressService;
import com.hs.user.service.UserService;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnboardingFacadeTest {
    private final UserService users=mock(UserService.class);
    private final OnboardingFacade facade=new OnboardingFacade(users,mock(AddressService.class),mock(BankAccountService.class));

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
}
