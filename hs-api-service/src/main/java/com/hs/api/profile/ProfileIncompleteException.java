package com.hs.api.profile;

import com.hs.common.advice.entity.AppException;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/** PROFILE_INCOMPLETE carrying the missing requirement codes for the {@code result.missing} payload. */
@Getter
public class ProfileIncompleteException extends AppException {
    private static final Map<String, String> LABELS = Map.of(
            "PROFILE", "Thông tin cá nhân", "ADDRESS", "Địa chỉ",
            "BANK_ACCOUNT", "Tài khoản ngân hàng", "KYC", "Xác minh danh tính (KYC)");

    private final transient List<String> missing;

    public ProfileIncompleteException(List<String> missing) {
        super(com.hs.common.advice.entity.enums.ErrorCode.PROFILE_INCOMPLETE, "Vui lòng cập nhật đầy đủ hồ sơ: "
                + String.join(", ", missing.stream().map(code -> LABELS.getOrDefault(code, code)).toList()));
        this.missing = List.copyOf(missing);
    }
}
