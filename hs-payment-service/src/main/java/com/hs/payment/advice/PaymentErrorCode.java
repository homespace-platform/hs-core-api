package com.hs.payment.advice;

import com.hs.common.advice.entity.AppException;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum PaymentErrorCode implements AppException.ErrorCode {
    BANK_ACCOUNT_NOT_FOUND(7001, "Tài khoản ngân hàng không tồn tại", HttpStatus.NOT_FOUND),
    BANK_ACCOUNT_FORBIDDEN(7002, "Bạn không có quyền thao tác trên tài khoản ngân hàng này", HttpStatus.FORBIDDEN),
    BANK_ACCOUNT_INACTIVE(7003, "Tài khoản ngân hàng đang bị vô hiệu hóa", HttpStatus.BAD_REQUEST),
    PAYMENT_REQUEST_NOT_FOUND(7004, "Yêu cầu thanh toán không tồn tại", HttpStatus.NOT_FOUND),
    PAYMENT_REQUEST_FORBIDDEN(7005, "Bạn không có quyền truy cập yêu cầu thanh toán này", HttpStatus.FORBIDDEN),
    INVALID_PAYMENT_STATUS(7006, "Trạng thái thanh toán không hợp lệ cho thao tác này", HttpStatus.BAD_REQUEST),
    PAYMENT_ALREADY_CONFIRMED(7007, "Yêu cầu thanh toán đã được xác nhận hoàn tất", HttpStatus.CONFLICT),
    PAYER_BANK_ACCOUNT_REQUIRED(7008, "Người thuê chưa cài đặt tài khoản ngân hàng mặc định nhận hoàn tiền", HttpStatus.BAD_REQUEST),
    PAYEE_BANK_ACCOUNT_REQUIRED(7009, "Chủ nhà chưa cài đặt tài khoản ngân hàng mặc định nhận thanh toán", HttpStatus.BAD_REQUEST),
    PAYMENT_TRANSFER_ALREADY_REPORTED(7010, "Khai báo chuyển khoản đã được ghi nhận", HttpStatus.CONFLICT),
    REJECTION_REASON_REQUIRED(7011, "Vui lòng nhập lý do từ chối xác nhận", HttpStatus.BAD_REQUEST),
    DISPUTE_NOT_ALLOWED(7012, "Chỉ có thể mở khiếu nại đối soát khi đã báo chuyển hoặc bị từ chối", HttpStatus.BAD_REQUEST),
    PAYMENT_HOLD_EXPIRED(7013, "Thời gian giữ chỗ thanh toán đã hết hạn", HttpStatus.BAD_REQUEST),
    BANK_ACCOUNT_DEFAULT_DELETE_FORBIDDEN(7014, "Không thể xóa hoặc tắt tài khoản đang là mặc định", HttpStatus.BAD_REQUEST),
    BANK_ACCOUNT_INVALID(7015, "Thông tin tài khoản ngân hàng không hợp lệ", HttpStatus.BAD_REQUEST),
    PAYMENT_PROOF_REQUIRED(7016, "Vui lòng tải lên ảnh hoặc tài liệu chứng từ chuyển khoản", HttpStatus.BAD_REQUEST),
    PAYMENT_PROOF_INVALID(7017, "Chứng từ chuyển khoản không hợp lệ hoặc không thuộc về yêu cầu này", HttpStatus.BAD_REQUEST),
    PROOF_SESSION_NOT_FOUND(7018, "Phiên tải chứng từ không tồn tại", HttpStatus.NOT_FOUND),
    PROOF_SESSION_EXPIRED(7019, "Phiên tải chứng từ đã hết hạn. Vui lòng tạo phiên mới.", HttpStatus.BAD_REQUEST),
    PROOF_SESSION_INVALID(7020, "Phiên tải chứng từ không hợp lệ hoặc đã được sử dụng", HttpStatus.BAD_REQUEST),
    PROOF_PUBLIC_BASE_URL_REQUIRED(7021, "Chưa cấu hình URL công khai để tải chứng từ từ điện thoại. Vui lòng cấu hình PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL.", HttpStatus.BAD_REQUEST),
    PROOF_PUBLIC_BASE_URL_INVALID(7022, "PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL không hợp lệ.", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;

    PaymentErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
