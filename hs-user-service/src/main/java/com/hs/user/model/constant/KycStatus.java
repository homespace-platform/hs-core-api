package com.hs.user.model.constant;

/** HomeSpace KYC lifecycle status (mapped from Didit verification statuses). */
public enum KycStatus {
    NOT_VERIFIED,
    PENDING,
    REVIEW_REQUIRED,
    VERIFIED,
    REJECTED,
    EXPIRED
}
