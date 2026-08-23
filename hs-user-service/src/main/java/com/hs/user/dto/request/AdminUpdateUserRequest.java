package com.hs.user.dto.request;

import static com.hs.user.validation.UserValidationPatterns.VIETNAMESE_PHONE;

import java.time.LocalDate;

import com.hs.user.model.constant.Gender;
import com.hs.user.validation.Adult;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminUpdateUserRequest(
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Username contains unsupported characters")
        String username,

        @Email(message = "Email format is invalid")
        @Size(max = 100, message = "Email must not exceed 100 characters")
        String email,

        @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
        String firstName,

        @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
        String lastName,

        @Pattern(regexp = VIETNAMESE_PHONE, message = "Vietnamese phone number format is invalid")
        String phone,

        @Adult(message = "User must be at least 18 years old")
        LocalDate dob,

        Gender gender,

        String roleId
) {
}
