package com.hs.user.model.constant;

public enum KycProvider {
    DIDIT("Didit"), // Retained only to read historical verifications; new sessions use VNPT.
    VNPT("VNPT eKYC"),
    ADMIN("Quản trị viên");

    private final String label;

    KycProvider(String label) { this.label = label; }

    /** Single source of the provider name shown to people. */
    public String label() { return label; }
}
