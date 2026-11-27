package com.hs.user.dto.request;

/** Manual KYC identity input from an ADMIN. Dates are ISO yyyy-MM-dd (stored as dd/MM/yyyy), gender is "Nam" or "Nữ". Scores and check flags are server-set, never accepted. */
public record AdminKycRequest(
        String cccd, String fullName, String birthDate, String gender, String permanentAddress,
        String issueDate, String expirationDate, String issuePlace
) {}
