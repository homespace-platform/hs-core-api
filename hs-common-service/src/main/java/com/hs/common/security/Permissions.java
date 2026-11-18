package com.hs.common.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of permission codes. Two scopes:
 * - admin scope: /admin/** endpoints require the ADMIN role AND the code; never grantable to USER.
 * - user scope ({@link #USER_SCOPE}): user-feature toggles an admin can grant/revoke on the USER role.
 * Codes must never equal a role name (ADMIN, USER): roles and permissions share one authority namespace.
 */
public final class Permissions {

    private Permissions() {
    }

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public static final String USER_VIEW = "USER_VIEW";
    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_UPDATE = "USER_UPDATE";
    public static final String ROLE_VIEW = "ROLE_VIEW";
    public static final String ROLE_UPDATE = "ROLE_UPDATE";
    public static final String PERMISSION_VIEW = "PERMISSION_VIEW";
    public static final String PERMISSION_UPDATE = "PERMISSION_UPDATE";
    public static final String KYC_VIEW = "KYC_VIEW";
    public static final String LISTING_VIEW = "LISTING_VIEW";
    public static final String LISTING_CREATE = "LISTING_CREATE";
    public static final String LISTING_UPDATE = "LISTING_UPDATE";
    public static final String LISTING_MODERATE = "LISTING_MODERATE";
    public static final String CONTRACT_TEMPLATE_VIEW = "CONTRACT_TEMPLATE_VIEW";
    public static final String CONTRACT_TEMPLATE_MANAGE = "CONTRACT_TEMPLATE_MANAGE";
    public static final String CONTRACT_TEMPLATE_PUBLISH = "CONTRACT_TEMPLATE_PUBLISH";
    public static final String CONTRACT_VIEW = "CONTRACT_VIEW";
    public static final String COMPLAINT_VIEW = "COMPLAINT_VIEW";
    public static final String COMPLAINT_RESOLVE = "COMPLAINT_RESOLVE";
    public static final String DISPUTE_VIEW = "DISPUTE_VIEW";
    public static final String DISPUTE_RESOLVE = "DISPUTE_RESOLVE";
    public static final String NEARBY_SCAN_VIEW = "NEARBY_SCAN_VIEW";
    public static final String NEARBY_SCAN_MANAGE = "NEARBY_SCAN_MANAGE";
    public static final String NOTIFICATION_BROADCAST = "NOTIFICATION_BROADCAST";
    public static final String INCIDENT_VIEW = "INCIDENT_VIEW";
    public static final String STORAGE_VIEW = "STORAGE_VIEW";

    // User scope: feature toggles for the USER role (ADMIN also holds them for its own account).
    public static final String LISTING_POST = "LISTING_POST";
    public static final String APPOINTMENT_BOOK = "APPOINTMENT_BOOK";
    public static final String APPOINTMENT_MANAGE = "APPOINTMENT_MANAGE";
    public static final String RENTAL_REQUEST_CREATE = "RENTAL_REQUEST_CREATE";
    public static final String RENTAL_REQUEST_MANAGE = "RENTAL_REQUEST_MANAGE";
    public static final String CONTRACT_MANAGE = "CONTRACT_MANAGE";
    public static final String CONTRACT_SIGN = "CONTRACT_SIGN";
    public static final String COMPLAINT_CREATE = "COMPLAINT_CREATE";
    public static final String PAYMENT_DISPUTE_CREATE = "PAYMENT_DISPUTE_CREATE";
    public static final String MEMBERSHIP_PURCHASE = "MEMBERSHIP_PURCHASE";
    public static final String FAVORITE_USE = "FAVORITE_USE";
    public static final String KYC_SUBMIT = "KYC_SUBMIT";

    /** Codes grantable to the USER role. Everything else in CATALOG is admin scope. */
    public static final java.util.Set<String> USER_SCOPE = java.util.Set.of(
            LISTING_POST, APPOINTMENT_BOOK, APPOINTMENT_MANAGE, RENTAL_REQUEST_CREATE, RENTAL_REQUEST_MANAGE,
            CONTRACT_MANAGE, CONTRACT_SIGN, COMPLAINT_CREATE, PAYMENT_DISPUTE_CREATE, MEMBERSHIP_PURCHASE,
            FAVORITE_USE, KYC_SUBMIT);

    public static boolean isUserScope(String code) {
        return USER_SCOPE.contains(code);
    }

    /** Code -> Vietnamese description, in display order. Seeded at startup; ADMIN gets all, USER gets USER_SCOPE on first seed. */
    public static final Map<String, String> CATALOG = catalog();

    private static Map<String, String> catalog() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(USER_VIEW, "Xem danh sách và chi tiết người dùng");
        m.put(USER_CREATE, "Tạo người dùng, gửi lại lời mời");
        m.put(USER_UPDATE, "Cập nhật, khóa/mở khóa người dùng");
        m.put(ROLE_VIEW, "Xem vai trò");
        m.put(ROLE_UPDATE, "Cập nhật quyền của vai trò, gán vai trò cho người dùng");
        m.put(PERMISSION_VIEW, "Xem quyền hạn");
        m.put(PERMISSION_UPDATE, "Cập nhật mô tả quyền hạn");
        m.put(KYC_VIEW, "Xem trạng thái KYC của người dùng");
        m.put(LISTING_VIEW, "Xem tin đăng (quản trị)");
        m.put(LISTING_CREATE, "Tạo tin đăng (quản trị)");
        m.put(LISTING_UPDATE, "Sửa tin đăng (quản trị)");
        m.put(LISTING_MODERATE, "Duyệt, đổi trạng thái tin đăng và yêu cầu sửa");
        m.put(CONTRACT_TEMPLATE_VIEW, "Xem mẫu hợp đồng, từ điển mã trường, xem thử");
        m.put(CONTRACT_TEMPLATE_MANAGE, "Tạo, sửa mẫu hợp đồng và phiên bản, tải tệp mẫu");
        m.put(CONTRACT_TEMPLATE_PUBLISH, "Phát hành phiên bản mẫu hợp đồng");
        m.put(CONTRACT_VIEW, "Xem hợp đồng hệ thống và tài liệu hợp đồng");
        m.put(COMPLAINT_VIEW, "Xem khiếu nại");
        m.put(COMPLAINT_RESOLVE, "Xử lý khiếu nại");
        m.put(DISPUTE_VIEW, "Xem tranh chấp thanh toán");
        m.put(DISPUTE_RESOLVE, "Xử lý tranh chấp thanh toán");
        m.put(NEARBY_SCAN_VIEW, "Xem job quét địa điểm lân cận");
        m.put(NEARBY_SCAN_MANAGE, "Tạo, hủy job quét địa điểm lân cận");
        m.put(NOTIFICATION_BROADCAST, "Gửi thông báo toàn hệ thống");
        m.put(INCIDENT_VIEW, "Xem sự cố vận hành");
        m.put(STORAGE_VIEW, "Xem tệp của người dùng (quản trị)");
        m.put(LISTING_POST, "Đăng tin, đổi trạng thái/ẩn tin của mình, quản lý chi nhánh");
        m.put(APPOINTMENT_BOOK, "Đặt, đổi, hủy lịch xem nhà");
        m.put(APPOINTMENT_MANAGE, "Chủ nhà duyệt, từ chối, hoàn tất lịch xem nhà");
        m.put(RENTAL_REQUEST_CREATE, "Gửi, hủy yêu cầu thuê");
        m.put(RENTAL_REQUEST_MANAGE, "Chủ nhà chấp nhận, từ chối yêu cầu thuê");
        m.put(CONTRACT_MANAGE, "Chủ nhà tạo, soạn và gửi hợp đồng");
        m.put(CONTRACT_SIGN, "Ký hợp đồng");
        m.put(COMPLAINT_CREATE, "Gửi khiếu nại");
        m.put(PAYMENT_DISPUTE_CREATE, "Mở tranh chấp thanh toán");
        m.put(MEMBERSHIP_PURCHASE, "Mua gói hội viên");
        m.put(FAVORITE_USE, "Lưu tin yêu thích");
        m.put(KYC_SUBMIT, "Xác minh danh tính (eKYC)");
        return java.util.Collections.unmodifiableMap(m);
    }
}
