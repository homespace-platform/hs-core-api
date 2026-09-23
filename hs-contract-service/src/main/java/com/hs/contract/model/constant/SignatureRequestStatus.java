package com.hs.contract.model.constant;

public enum SignatureRequestStatus {
    CREATED,
    PENDING_USER_CONFIRMATION,
    /**
     * VNPT đã ký (signature_value đã nhận); worker sẽ nhúng vào PDF.
     */
    PROVIDER_SIGNED,
    EMBEDDING,
    SIGNED,
    REJECTED,
    EXPIRED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SIGNED || this == REJECTED || this == EXPIRED
                || this == FAILED || this == CANCELLED;
    }

    public boolean canRetry() {
        return this == REJECTED || this == EXPIRED || this == FAILED;
    }

    public boolean isEmbeddingPhase() {
        return this == PROVIDER_SIGNED || this == EMBEDDING;
    }
}
