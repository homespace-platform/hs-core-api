package com.hs.common.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Immutable policy versions shared by consent capture and deposit rendering. Never edit a version. */
public final class ReservationPolicy {
    private ReservationPolicy() {}
    public static final String VERSION="RESERVATION_DEFAULT_V2";
    public static final String V1_TEXT="cọc và tiền thuê được theo dõi riêng. Nếu tôi quá hạn trả tháng đầu hoặc quá hạn ký sau khi chủ nhà đã ký và cung cấp văn bản, chủ nhà có thể giữ 100% cọc thực nhận và hủy hồ sơ sau thông báo cuối cùng ít nhất 24 giờ. Tôi có quyền phản hồi, yêu cầu đối soát; tiền thuê đã nhận phải hoàn riêng, không bị giữ cùng cọc.";
    public static final String TEXT=V1_TEXT+" Với hồ sơ có cọc lớn hơn 0, sau xác nhận tiền, chủ nhà và người thuê ký số hợp đồng cọc trước khi ký hợp đồng thuê, kể cả nhận nhà ngay. Miễn cọc không phát sinh hợp đồng nhận tiền cọc. Xác nhận nhận tiền và kết quả ký là các sự kiện riêng; ký cọc không xác nhận đã bàn giao hoặc bắt đầu thuê. Không tự mất cọc do hết thời gian giữ chỗ, thiếu mẫu, lỗi kết xuất/ký số hoặc chưa có văn bản sẵn sàng để xem/ký. Khi có phản đối hoặc giao dịch/chữ ký cần đối soát, chưa được chốt giữ cọc. Lỗi phía chủ nhà và nghĩa vụ hoàn/trách nhiệm được xử lý theo thỏa thuận hợp lệ, chứng cứ và pháp luật.";
    public static String hash(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
