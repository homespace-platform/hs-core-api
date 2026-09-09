package com.hs.contract.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Kết quả đối chiếu dữ liệu hợp đồng với các mã trường thực tế có trong file mẫu Word.
 * Chủ nhà dựa vào đây để biết còn thiếu gì trước khi tải bản nháp xuống kiểm tra và gửi cho người thuê.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractCompletenessResponse {

    private String contractId;
    private String revisionId;

    /** Không còn mã trường nào trong mẫu bị bỏ trống. */
    private boolean complete;

    /** Tổng số mã trường (không tính bảng động) mà file mẫu đang dùng. */
    private int totalFields;
    private int filledFields;

    private List<MissingField> missingFields;

    /** Cảnh báo không chặn: bảng động rỗng, dữ liệu nhìn bất thường... */
    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissingField {
        /** Mã trường như trong mẫu, ví dụ {@code landlord.idNumber}. */
        private String key;
        private String label;
        private String group;
        /** Nhóm snapshot chứa trường này, để frontend biết cần sửa ở form nào. */
        private String section;
    }
}
