package com.hs.user.dto.request;

import static com.hs.user.validation.UserValidationPatterns.VIETNAMESE_PHONE;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminCreateUserRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Username contains unsupported characters")
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        @Size(max = 100, message = "Email must not exceed 100 characters")
        String email,

        @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
        String firstName,

        @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
        String lastName,

        @Pattern(regexp = VIETNAMESE_PHONE, message = "Vietnamese phone number format is invalid")
        String phone,

        Boolean enabled,
        Boolean sendInvitation
) {
    public AdminCreateUserRequest {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        sendInvitation = sendInvitation == null ? Boolean.TRUE : sendInvitation;
    }
}
