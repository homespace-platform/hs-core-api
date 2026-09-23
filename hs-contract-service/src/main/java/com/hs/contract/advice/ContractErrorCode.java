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
    CONTRACT_TEMPLATE_INVALID(6009, "Không thể xuất bản: mẫu Word còn mã trường không hợp lệ hoặc thiếu trường bắt buộc. Vui lòng tải lên phiên bản đã sửa lỗi.", HttpStatus.BAD_REQUEST),
    RENTAL_REQUEST_NOT_APPROVED(6010, "Yêu cầu thuê chưa được chủ nhà chấp thuận", HttpStatus.BAD_REQUEST),
    CONTRACT_DOCUMENT_NOT_FOUND(6011, "Tài liệu hợp đồng không tồn tại", HttpStatus.NOT_FOUND),
    RENTAL_REQUEST_ALREADY_HAS_CONTRACT(6012, "Yêu cầu thuê này đã có hợp đồng được tạo", HttpStatus.CONFLICT),
    STORAGE_FILE_READ_FAILED(6013, "Không thể đọc tệp tài liệu mẫu từ kho lưu trữ", HttpStatus.INTERNAL_SERVER_ERROR),
    CONTRACT_DATA_INCOMPLETE(6014, "Không thể kết xuất: hợp đồng vẫn còn trường bắt buộc chưa được điền", HttpStatus.BAD_REQUEST),
    CONTRACT_DOCUMENT_REQUIRED(6015, "Không thể gửi: hợp đồng chưa có tài liệu hoàn chỉnh", HttpStatus.BAD_REQUEST),
    CONTRACT_PAYMENT_NOT_ALLOWED(6016, "Hợp đồng không ở trạng thái cho phép thanh toán", HttpStatus.CONFLICT),
    CONTRACT_SIGNING_NOT_ALLOWED(6017, "Hợp đồng chưa được thanh toán hoặc không thể ký ở trạng thái hiện tại", HttpStatus.CONFLICT),
    CONTRACT_PAYMENT_DATA_INVALID(6018, "Dữ liệu tiền thuê hoặc tiền cọc trong hợp đồng không hợp lệ", HttpStatus.UNPROCESSABLE_ENTITY),
    RENTAL_PAYMENT_REQUIRED_BEFORE_CONTRACT(6019, "Hợp đồng chỉ có thể được tạo sau khi khách thuê hoàn tất thanh toán ban đầu", HttpStatus.CONFLICT),

    // SmartCA signature errors (6020–6039)
    SIGNATURE_IDENTITY_MISSING(6020, "Tài khoản chưa hoàn tất xác minh CCCD — vui lòng hoàn thành định danh trước khi ký số", HttpStatus.FORBIDDEN),
    SIGNATURE_CERTIFICATE_NOT_FOUND(6021, "Không tìm thấy chứng thư số SmartCA hợp lệ gắn với CCCD của bạn. Vui lòng mở ứng dụng VNPT SmartCA để kích hoạt chứng thư.", HttpStatus.NOT_FOUND),
    SIGNATURE_PROVIDER_UNAVAILABLE(6022, "Cổng VNPT SmartCA hiện không khả dụng — vui lòng thử lại sau", HttpStatus.SERVICE_UNAVAILABLE),
    SIGNATURE_PROVIDER_AUTH_FAILED(6023, "Xác thực tài khoản tích hợp VNPT SmartCA thất bại — vui lòng liên hệ quản trị viên", HttpStatus.INTERNAL_SERVER_ERROR),
    SIGNATURE_PROVIDER_ACCESS_DENIED(6024, "Cổng VNPT SmartCA từ chối quyền truy cập — chứng thư không hợp lệ hoặc đã hết hạn", HttpStatus.FORBIDDEN),
    SIGNATURE_PROVIDER_INVALID_REQUEST(6025, "Yêu cầu ký số gửi lên VNPT SmartCA không hợp lệ", HttpStatus.BAD_REQUEST),
    SIGNATURE_REQUEST_ALREADY_PENDING(6026, "Đã có yêu cầu ký số đang chờ xác nhận — vui lòng mở ứng dụng VNPT SmartCA để hoàn tất", HttpStatus.CONFLICT),
    SIGNATURE_REQUEST_NOT_FOUND(6027, "Yêu cầu ký số không tồn tại", HttpStatus.NOT_FOUND),
    SIGNATURE_NOT_ALLOWED(6028, "Bạn không có quyền ký hợp đồng này hoặc hợp đồng chưa sẵn sàng để ký", HttpStatus.FORBIDDEN),
    SIGNATURE_PDF_NOT_READY(6029, "Tài liệu PDF chưa sẵn sàng — vui lòng kết xuất lại trước khi ký số", HttpStatus.BAD_REQUEST),
    SIGNATURE_PDF_EMBED_FAILED(6030, "Không thể nhúng chữ ký số vào PDF — dữ liệu chữ ký từ VNPT không hợp lệ", HttpStatus.INTERNAL_SERVER_ERROR),
    SIGNATURE_RETRY_NOT_ALLOWED(6031, "Yêu cầu ký số không thể thử lại ở trạng thái hiện tại", HttpStatus.CONFLICT),
    SIGNATURE_MODE_NOT_ENABLED(6032, "Tính năng ký số VNPT SmartCA chưa được kích hoạt trên hệ thống", HttpStatus.SERVICE_UNAVAILABLE),
    SIGNATURE_PDF_VERIFICATION_FAILED(6033, "Xác minh chữ ký số trong PDF thất bại — dữ liệu có thể bị thay đổi", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;

    ContractErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
