package com.hs.contract.dto.signature;

import lombok.Data;

/**
 * Body của POST /contracts/{id}/signatures/initiate
 */
@Data
public class InitiateSignatureRequest {
    /**
     * Serial number của chứng thư chọn (null = tự động chọn cert hợp lệ duy nhất).
     */
    private String certificateSerial;

    /**
     * User đã đồng ý với điều khoản ký số.
     * Frontend phải gửi {@code true}; backend từ chối nếu thiếu.
     */
    private boolean consent;
}
