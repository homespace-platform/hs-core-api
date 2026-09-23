package com.hs.contract.model.constant;

public enum DocumentPurpose {
    PREVIEW,
    OFFICIAL,
    /** PDF đã có chữ ký số của chủ nhà, chờ người thuê ký. */
    SIGNED_LANDLORD,
    /** PDF cuối đã có chữ ký số của cả hai bên — phiên bản hoàn chỉnh. */
    SIGNED_FINAL
}
