package com.hs.contract.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.contract.model.constant.SignatureRequestStatus;
import com.hs.contract.model.constant.SignerRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Lưu trữ từng giao dịch ký số SmartCA (một lần ký của một bên).
 *
 * <p>Luồng trạng thái:</p>
 * <pre>
 * CREATED → PENDING_USER_CONFIRMATION → PROVIDER_SIGNED → EMBEDDING → SIGNED
 *                                    ↘ REJECTED / EXPIRED / FAILED
 * </pre>
 *
 * <p><b>Quan trọng:</b> Không gọi prepareSignature() lần thứ hai sau khi VNPT đã trả về
 * signature_value. Các field {@code prepared_*} chỉ được ghi một lần lúc tạo request.</p>
 */
@Entity
@Table(name = "signature_requests", indexes = {
        @Index(name = "idx_sig_req_contract", columnList = "contract_id, signer_role"),
        @Index(name = "idx_sig_req_status", columnList = "status"),
        @Index(name = "idx_sig_req_doc_id", columnList = "doc_id"),
        @Index(name = "idx_sig_req_tran_code", columnList = "provider_tran_code")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignatureRequest extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "contract_id", nullable = false, length = 36)
    private String contractId;

    @Column(name = "revision_id", nullable = false, length = 36)
    private String revisionId;

    @Column(name = "source_document_id", nullable = false, length = 36)
    private String sourceDocumentId;

    /** ID tài liệu đã ký (chỉ tồn tại sau khi SIGNED). */
    @Column(name = "signed_document_id", length = 36)
    private String signedDocumentId;

    @Column(name = "signer_user_id", nullable = false, length = 36)
    private String signerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signer_role", nullable = false, length = 20)
    private SignerRole signerRole;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SignatureRequestStatus status = SignatureRequestStatus.CREATED;

    // -----------------------------------------------------------------------
    // VNPT SmartCA transaction identifiers
    // -----------------------------------------------------------------------

    /**
     * doc_id dùng trong sign_files: phải là chuỗi duy nhất (dùng UUID).
     * Dùng để match signature trong response status.
     */
    @Column(name = "doc_id", nullable = false, length = 100)
    private String docId;

    /** transaction_id (HomeSpace tạo ra) gửi lên VNPT. */
    @Column(name = "provider_transaction_id", length = 100)
    private String providerTransactionId;

    /** tran_code hoặc tran_id mà VNPT trả về — dùng để query status. */
    @Column(name = "provider_tran_code", length = 100)
    private String providerTranCode;

    // -----------------------------------------------------------------------
    // Certificate info
    // -----------------------------------------------------------------------

    @Column(name = "certificate_serial", length = 128)
    private String certificateSerial;

    @Column(name = "certificate_subject", columnDefinition = "TEXT")
    private String certificateSubject;

    @Column(name = "signer_cccd", length = 20)
    private String signerCccd;

    // -----------------------------------------------------------------------
    // Prepared PDF staging — ghi một lần lúc tạo, KHÔNG ghi lại sau đó
    // -----------------------------------------------------------------------

    /** Storage object ID của prepared PDF (chứa signature placeholder). */
    @Column(name = "prepared_document_storage_id", length = 36)
    private String preparedDocumentStorageId;

    /**
     * ByteRange lưu dạng "p0,l0,p1,l1" — 4 số nguyên cách nhau dấu phẩy.
     * Cần để reconstruct PreparedPdfSignature khi nhúng chữ ký.
     */
    @Column(name = "prepared_byte_range", length = 100)
    private String preparedByteRange;

    /** Offset của vùng placeholder hex trong file (để nhúng signatureValue đúng chỗ). */
    @Column(name = "prepared_placeholder_offset")
    private Long preparedPlaceholderOffset;

    /** Độ dài (byte) của vùng placeholder. */
    @Column(name = "prepared_placeholder_length")
    private Integer preparedPlaceholderLength;

    /** signDate chính xác đã dùng khi prepare (phải khớp với những gì đã hash). */
    @Column(name = "prepared_sign_date")
    private Instant preparedSignDate;

    // -----------------------------------------------------------------------
    // Provider signature staging — set khi VNPT trả SIGNED, trước khi embed
    // -----------------------------------------------------------------------

    /** Raw signature_value từ VNPT (base64 hoặc hex). KHÔNG log. */
    @Column(name = "provider_signature_value", columnDefinition = "TEXT")
    private String providerSignatureValue;

    /** cert_data từ VNPT (base64 DER). KHÔNG log. */
    @Column(name = "provider_cert_data", columnDefinition = "TEXT")
    private String providerCertData;

    /** chain_data từ VNPT. KHÔNG log. */
    @Column(name = "provider_chain_data", columnDefinition = "TEXT")
    private String providerChainData;

    // -----------------------------------------------------------------------
    // Audit & timing
    // -----------------------------------------------------------------------

    @Column(name = "initiated_at")
    private Instant initiatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Builder.Default
    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber = 1;

    /** Thời điểm embedding worker bắt đầu xử lý (dùng để detect stuck jobs). */
    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    /** Số lần đã cố gắng embed (giới hạn tối đa 3 lần). */
    @Builder.Default
    @Column(name = "processing_attempts", nullable = false)
    private int processingAttempts = 0;

    /** Lần cuối poll VNPT (tránh thundering-herd khi nhiều client cùng refresh). */
    @Column(name = "last_provider_checked_at")
    private Instant lastProviderCheckedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = SignatureRequestStatus.CREATED;
        }
    }
}
