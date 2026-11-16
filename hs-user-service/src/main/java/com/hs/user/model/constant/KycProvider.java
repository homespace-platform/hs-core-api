package com.hs.user.model.constant;

public enum KycProvider {
    DIDIT, // Retained only to read historical verifications; new sessions use VNPT.
    VNPT
}
