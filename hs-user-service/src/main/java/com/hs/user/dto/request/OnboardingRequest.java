package com.hs.user.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.hs.user.validation.UserValidationPatterns.VIETNAMESE_PHONE;

public record OnboardingRequest(
        @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
        String firstName,

        @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
        String lastName,

        @Pattern(regexp = VIETNAMESE_PHONE, message = "Vietnamese phone number format is invalid")
        String phone
) {
}

