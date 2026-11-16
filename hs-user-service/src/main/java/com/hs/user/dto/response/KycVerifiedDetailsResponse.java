package com.hs.user.dto.response;

public record KycVerifiedDetailsResponse(
        String cccd, String fullName, String birthDate, String permanentAddress,
        String gender, String issueDate, String expirationDate, String issuePlace,
        Double faceMatchPercent,
        Boolean documentValid, Boolean frontBackIdentical, Boolean clearCorners,
        Boolean idQualityGood, Boolean issueDateQualityGood, Boolean unexpired,
        Boolean liveDocument, Boolean noWarnings, Boolean noOverlayImage,
        Boolean eyesOpen, Boolean faceNotBlurred, Boolean faceValid,
        Boolean faceNotCovered
) {}
