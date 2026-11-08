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
    PROOF_CONFLICT(6082, "Dữ liệu proof không khớp tài liệu nguồn; cần quản trị viên đối soát", HttpStatus.CONFLICT);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;
}
