package com.hs.listing.model.constant;

/** Hiện trạng bàn giao của một tài sản/trang thiết bị trong biên bản bàn giao. */
public enum HandoverCondition {
    BRAND_NEW("Mới 100%"),
    GOOD("Còn tốt"),
    NORMAL("Bình thường"),
    USED_ACCEPTABLE("Cũ, còn dùng được"),
    MINOR_DAMAGE("Hư hỏng nhẹ");

    private final String label;

    HandoverCondition(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
