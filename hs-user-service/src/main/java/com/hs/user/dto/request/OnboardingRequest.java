package com.hs.user.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

import com.hs.user.model.constant.Gender;
import com.hs.user.validation.Adult;

import static com.hs.user.validation.UserValidationPatterns.VIETNAMESE_PHONE;

public record OnboardingRequest(
        @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
        String firstName,

        @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
        String lastName,

        @Pattern(regexp = VIETNAMESE_PHONE, message = "Vietnamese phone number format is invalid")
        String phone,

        @Adult(message = "User must be at least 18 years old")
        LocalDate dob,

        Gender gender
) {
}

