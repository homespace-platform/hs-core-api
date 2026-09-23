package com.hs.contract.dto.signature;

import com.hs.contract.model.constant.SignatureRequestStatus;
import com.hs.contract.model.constant.SignerRole;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SignatureRequestDto {
    private String id;
    private String contractId;
    private String revisionId;
    private SignerRole signerRole;
    private SignatureRequestStatus status;
    private String docId;
    private String signedDocumentId;
    private String certificateSerial;
    private String certificateSubject;
    private Instant initiatedAt;
    private Instant confirmedAt;
    private Instant expiresAt;
    private Instant failedAt;
    private String failureCode;
    private String failureMessage;
    private int attemptNumber;
    /** Số lần thử poll VNPT tích lũy (dùng cho retry UI). */
    private int processingAttempts;
    /** Tin nhắn UI-friendly về lý do thất bại (không chứa thông tin kỹ thuật nhạy cảm). */
    private String failureReason;
}
