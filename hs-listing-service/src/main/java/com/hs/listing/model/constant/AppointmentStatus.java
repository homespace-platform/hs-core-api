package com.hs.listing.model.constant;

public enum AppointmentStatus {
    PENDING,    // Đang chờ chủ nhà duyệt
    CONFIRMED,  // Chủ nhà đã chấp nhận (Khóa khung giờ đó lại)
    REJECTED,   // Chủ nhà từ chối
    CANCELLED,  // Khách hoặc chủ nhà hủy
    COMPLETED,  // Đã hoàn thành buổi xem nhà
    EXPIRED     // Hết hạn (chủ nhà không phản hồi kịp trước giờ hẹn)
}
