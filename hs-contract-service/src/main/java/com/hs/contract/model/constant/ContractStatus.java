package com.hs.contract.model.constant;

public enum ContractStatus {
    DRAFT,
    PENDING_REVIEW,
    /** Chủ nhà đã gửi yêu cầu ký SmartCA, chờ xác nhận trên ứng dụng. */
    LANDLORD_SIGNATURE_PENDING,
    /** Chủ nhà đã ký, đang chờ người thuê ký SmartCA. */
    TENANT_SIGNATURE_PENDING,
    ACTIVE,
    TERMINATED,
    CANCELLED
}
