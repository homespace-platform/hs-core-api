package com.hs.contract.advice;

import com.hs.common.advice.entity.AppException;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum ContractErrorCode implements AppException.ErrorCode {
    CONTRACT_TEMPLATE_NOT_FOUND(6001, "Mẫu hợp đồng không tồn tại", HttpStatus.NOT_FOUND),
    CONTRACT_TEMPLATE_VERSION_NOT_FOUND(6002, "Phiên bản mẫu hợp đồng không tồn tại", HttpStatus.NOT_FOUND),
    CONTRACT_NOT_FOUND(6003, "Hợp đồng không tồn tại", HttpStatus.NOT_FOUND),
    CONTRACT_REVISION_NOT_FOUND(6004, "Bản sửa đổi hợp đồng không tồn tại", HttpStatus.NOT_FOUND),
    CONTRACT_FORBIDDEN(6005, "Bạn không có quyền truy cập hoặc chỉnh sửa hợp đồng này", HttpStatus.FORBIDDEN),
    INVALID_CONTRACT_STATUS(6006, "Trạng thái hợp đồng không hợp lệ cho thao tác này", HttpStatus.BAD_REQUEST),
    CONTRACT_RENDER_FAILED(6007, "Không thể điền dữ liệu vào mẫu hợp đồng Word", HttpStatus.INTERNAL_SERVER_ERROR),
    CONTRACT_CONVERT_FAILED(6008, "Lỗi khi chuyển đổi hợp đồng sang tài liệu PDF", HttpStatus.INTERNAL_SERVER_ERROR),
    CONTRACT_TEMPLATE_INVALID(6009, "Mẫu hợp đồng chứa các mã trường không hợp lệ", HttpStatus.BAD_REQUEST),
    RENTAL_REQUEST_NOT_APPROVED(6010, "Yêu cầu thuê chưa được chủ nhà chấp thuận", HttpStatus.BAD_REQUEST),
    CONTRACT_DOCUMENT_NOT_FOUND(6011, "Tài liệu hợp đồng không tồn tại", HttpStatus.NOT_FOUND),
    RENTAL_REQUEST_ALREADY_HAS_CONTRACT(6012, "Yêu cầu thuê này đã có hợp đồng được tạo", HttpStatus.CONFLICT),
    STORAGE_FILE_READ_FAILED(6013, "Không thể đọc tệp tài liệu mẫu từ kho lưu trữ", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;

    ContractErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
