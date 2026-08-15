package com.hs.user.dto.request;

import java.time.LocalDate;

import com.hs.user.model.constant.Gender;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Username may contain only letters, numbers, periods, underscores, and hyphens")
        String username,

        @Email(message = "Email must be valid")
        @Size(max = 100, message = "Email must not exceed 100 characters")
        String email,

        @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
        String firstName,

        @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
        String lastName,

        @Pattern(regexp = "^[0-9]{10,15}$", message = "Phone number must contain between 10 and 15 digits")
        String phone,

        @Past(message = "Date of birth must be in the past")
        LocalDate dob,

        Gender gender
) {
}

