package com.hs.contract.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Cấu hình tích hợp VNPT SmartCA.
 *
 * <p>Tất cả giá trị được bind từ biến môi trường qua prefix {@code homespace.smartca}.</p>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "homespace.smartca")
public class SmartCaProperties {

    /** true khi chạy ở chế độ SMARTCA thực (false = INTERNAL legacy sign). */
    private boolean enabled = false;

    /** Base URL của VNPT SmartCA API (ví dụ: https://gateway.smartca.vnpt.vn/api). */
    private String baseUrl;

    /** SP ID do VNPT cấp cho HomeSpace. */
    private String spId;

    /**
     * SP Password do VNPT cấp.
     * KHÔNG LOG, KHÔNG đưa vào response body hay frontend.
     */
    private String spPassword;

    /** Timeout kết nối tới VNPT API. */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** Timeout đọc response từ VNPT API. */
    private Duration readTimeout = Duration.ofSeconds(30);

    /** Chu kỳ scheduler poll trạng thái ký. */
    private Duration pollInterval = Duration.ofSeconds(5);

    /** Thời gian tối đa chờ người dùng xác nhận trên app VNPT SmartCA. */
    private Duration transactionTimeout = Duration.ofMinutes(5);

    /** Webhook có bật không (bổ sung, không phải luồng chính). */
    private boolean webhookEnabled = false;

    /** URL public để VNPT gọi callback vào (chỉ dùng khi webhookEnabled=true). */
    private String webhookPublicUrl;

    /**
     * Kiểm tra cấu hình tối thiểu cần thiết để gọi VNPT API.
     * Gọi trước mỗi lần gọi API để fail fast với thông báo rõ ràng.
     */
    public void requireConfigured() {
        if (!enabled) {
            throw new IllegalStateException("SmartCA integration is not enabled (homespace.smartca.enabled=false)");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("homespace.smartca.base-url is not configured");
        }
        if (spId == null || spId.isBlank()) {
            throw new IllegalStateException("homespace.smartca.sp-id is not configured");
        }
        if (spPassword == null || spPassword.isBlank()) {
            throw new IllegalStateException("homespace.smartca.sp-password is not configured");
        }
    }
}
