package com.hs.user.dto.request;

import jakarta.validation.constraints.*;

import java.time.LocalDate;

import com.hs.user.model.constant.Gender;
import com.hs.user.validation.Adult;

import static com.hs.user.validation.UserValidationPatterns.VIETNAMESE_PHONE;

public record OnboardingRequest(
        @NotBlank(message = "First name must not be blank")
        @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
        String firstName,

        @NotBlank(message = "Last name must not be blank")
        @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
        String lastName,

        @NotBlank(message = "Phone number must not be blank")
        @Pattern(regexp = VIETNAMESE_PHONE, message = "Vietnamese phone number format is invalid")
        String phone,

        @NotNull(message = "Date of birth must not be null")
        @Adult(message = "User must be at least 18 years old")
        LocalDate dob,

        @NotNull(message = "Gender must not be null")
        Gender gender
) {
}

