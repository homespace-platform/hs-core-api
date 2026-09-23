package com.hs.contract.dto.signature;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Trạng thái ký số tổng hợp của một hợp đồng — trả về cho frontend.
 */
@Data
@Builder
public class SignatureStateDto {
    /** Chế độ ký: INTERNAL | SMARTCA */
    private String signatureMode;
    /** Có bật SmartCA không. */
    private boolean smartCaEnabled;
    /** Thông tin yêu cầu ký đang active của người dùng hiện tại (nếu có). */
    private SignatureRequestDto activeRequest;
    /** Danh sách lịch sử các yêu cầu ký cho hợp đồng này (tất cả các bên). */
    private List<SignatureRequestDto> allRequests;
    /** Danh sách chứng thư có thể dùng (chỉ trả về khi cần chọn cert). */
    private List<CertificateOptionDto> availableCertificates;
}
