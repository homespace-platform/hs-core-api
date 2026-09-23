package com.hs.contract.dto.signature;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Thông tin chứng thư SmartCA để hiển thị trong modal chọn chứng thư.
 */
@Data
@Builder
public class CertificateOptionDto {
    private String serialNumber;
    private String subject;
    private String issuer;
    private Instant validFrom;
    private Instant validTo;
}
