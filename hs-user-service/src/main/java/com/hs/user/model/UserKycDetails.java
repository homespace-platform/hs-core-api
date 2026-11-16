package com.hs.user.model;

import com.hs.user.dto.response.KycVerifiedDetailsResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_kyc_details", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
        name = "uk_user_kyc_details_cccd", columnNames = "cccd"))
@Getter @Setter @NoArgsConstructor
public class UserKycDetails {
    @Id @Column(name = "user_id", length = 36, nullable = false)
    private String userId;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_user_kyc_details_user"))
    private User user;
    @Column(length = 20)
    private String cccd;
    @Column(name = "full_name", columnDefinition = "text")
    private String fullName;
    @Column(name = "birth_date", length = 20)
    private String birthDate;
    @Column(name = "permanent_address", columnDefinition = "text")
    private String permanentAddress;
    @Column(length = 20)
    private String gender;
    @Column(name = "issue_date", length = 20)
    private String issueDate;
    @Column(name = "expiration_date", length = 20)
    private String expirationDate;
    @Column(name = "issue_place", columnDefinition = "text")
    private String issuePlace;
    @Column(name = "face_match_percent")
    private Double faceMatchPercent;
    private Boolean documentValid;
    private Boolean frontBackIdentical;
    private Boolean clearCorners;
    private Boolean idQualityGood;
    private Boolean issueDateQualityGood;
    private Boolean unexpired;
    private Boolean liveDocument;
    private Boolean noWarnings;
    private Boolean noOverlayImage;
    private Boolean eyesOpen;
    private Boolean faceNotBlurred;
    private Boolean faceValid;
    private Boolean faceNotCovered;

    public static UserKycDetails from(String userId, KycVerifiedDetailsResponse value) {
        var details = new UserKycDetails();
        details.userId = userId;
        details.cccd = value.cccd();
        details.fullName = value.fullName();
        details.birthDate = value.birthDate();
        details.permanentAddress = value.permanentAddress();
        details.gender = value.gender();
        details.issueDate = value.issueDate();
        details.expirationDate = value.expirationDate();
        details.issuePlace = value.issuePlace();
        details.faceMatchPercent = value.faceMatchPercent();
        details.documentValid = value.documentValid();
        details.frontBackIdentical = value.frontBackIdentical();
        details.clearCorners = value.clearCorners();
        details.idQualityGood = value.idQualityGood();
        details.issueDateQualityGood = value.issueDateQualityGood();
        details.unexpired = value.unexpired();
        details.liveDocument = value.liveDocument();
        details.noWarnings = value.noWarnings();
        details.noOverlayImage = value.noOverlayImage();
        details.eyesOpen = value.eyesOpen();
        details.faceNotBlurred = value.faceNotBlurred();
        details.faceValid = value.faceValid();
        details.faceNotCovered = value.faceNotCovered();
        return details;
    }

    public KycVerifiedDetailsResponse toResponse() {
        return new KycVerifiedDetailsResponse(cccd, fullName, birthDate, permanentAddress,
                gender, issueDate, expirationDate, issuePlace, faceMatchPercent,
                documentValid, frontBackIdentical, clearCorners, idQualityGood,
                issueDateQualityGood, unexpired, liveDocument, noWarnings,
                noOverlayImage, eyesOpen, faceNotBlurred, faceValid, faceNotCovered);
    }
}
