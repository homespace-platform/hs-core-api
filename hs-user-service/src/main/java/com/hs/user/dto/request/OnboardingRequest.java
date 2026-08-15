package com.hs.user.dto.request;

import jakarta.validation.constraints.*;

import java.time.LocalDate;

import com.hs.user.model.constant.Gender;

public record OnboardingRequest(
        @NotBlank(message = "First name must not be blank")
        @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
        String firstName,

        @NotBlank(message = "Last name must not be blank")
        @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
        String lastName,

        @NotBlank(message = "Phone number must not be blank")
        @Pattern(regexp = "^[0-9]{10,15}$", message = "Phone number must contain between 10 and 15 digits")
        String phone,

        @NotNull(message = "Date of birth must not be null")
        @Past(message = "Date of birth must be in the past")
        LocalDate dob,

        @NotNull(message = "Gender must not be null")
        Gender gender
) {
}

