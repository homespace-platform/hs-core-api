package com.hs.contract.blockchain.api;

import com.hs.common.advice.entity.AppException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
@RequiredArgsConstructor
public enum BlockchainProofError implements AppException.ErrorCode {
    PROOF_NOT_AVAILABLE(6080, "Tài liệu chưa có proof blockchain để đối chiếu", HttpStatus.CONFLICT),
    BLOCKCHAIN_UNAVAILABLE(6081, "Chưa kết nối được blockchain; vui lòng thử lại, chưa thể kết luận hash có khớp hay không", HttpStatus.SERVICE_UNAVAILABLE),
    PROOF_CONFLICT(6082, "Dữ liệu proof không khớp tài liệu nguồn; cần quản trị viên đối soát", HttpStatus.CONFLICT),
    DOCUMENT_INTEGRITY_MISMATCH(6083, "PDF không khớp bản đã ký; cần quản trị viên đối soát", HttpStatus.CONFLICT),
    DOCUMENT_CONTENT_UNAVAILABLE(6084, "Chưa xác thực được PDF; vui lòng thử lại", HttpStatus.SERVICE_UNAVAILABLE),
    DOCUMENT_CONTENT_MISSING(6085, "Không tìm thấy PDF đã lưu; cần khôi phục tài liệu", HttpStatus.NOT_FOUND),
    DOCUMENT_VERSION_REQUIRED(6086, "PDF ký đủ cần S3 Versioning Enabled và Version ID hợp lệ", HttpStatus.CONFLICT);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;
}
