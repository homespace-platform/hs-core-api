package com.hs.payment.service;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PaymentProofUrlResolverTest {

    private PaymentProofUrlResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PaymentProofUrlResolver();
    }

    @Test
    @DisplayName("1. Có configured public base URL: Chuẩn hóa đúng URL")
    void testValidConfiguredPublicBaseUrl() {
        String input = "https://13zp4kj8-8080.asse.devtunnels.ms";
        String normalized = resolver.normalizePublicBaseUrl(input);
        assertEquals("https://13zp4kj8-8080.asse.devtunnels.ms", normalized);

        String resolved = resolver.resolvePublicBaseUrl(
                input,
                "http",
                "172.31.34.19:8081",
                "172.31.34.19:8081",
                "172.31.34.19",
                8081
        );
        assertEquals("https://13zp4kj8-8080.asse.devtunnels.ms", resolved);
    }

    @Test
    @DisplayName("2. Base URL có slash cuối: Kết quả không bị double slash")
    void testTrailingSlashRemoval() {
        String inputWithSlash = "https://13zp4kj8-8080.asse.devtunnels.ms/";
        String inputWithMultiSlash = "https://13zp4kj8-8080.asse.devtunnels.ms///";

        assertEquals("https://13zp4kj8-8080.asse.devtunnels.ms", resolver.normalizePublicBaseUrl(inputWithSlash));
        assertEquals("https://13zp4kj8-8080.asse.devtunnels.ms", resolver.normalizePublicBaseUrl(inputWithMultiSlash));
    }

    @Test
    @DisplayName("3. Base URL có whitespace: Được trim tự động")
    void testWhitespaceTrim() {
        String inputWithWhitespace = "   https://api.homespace.vn   ";
        assertEquals("https://api.homespace.vn", resolver.normalizePublicBaseUrl(inputWithWhitespace));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "file:///etc/passwd",
            "ftp://files.example.com",
            "data:text/plain;base64,SGVsbG8=",
            "ws://socket.example.com"
    })
    @DisplayName("4. Base URL protocol không hợp lệ: Bị từ chối PROOF_PUBLIC_BASE_URL_INVALID")
    void testInvalidProtocolsRejected(String invalidUrl) {
        AppException ex = assertThrows(AppException.class, () -> resolver.normalizePublicBaseUrl(invalidUrl));
        assertEquals(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_INVALID.getCode(), ex.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.homespace.vn?foo=bar",
            "https://api.homespace.vn/?query=1",
            "https://13zp4kj8-8080.asse.devtunnels.ms#section",
            "https://13zp4kj8-8080.asse.devtunnels.ms/#fragment"
    })
    @DisplayName("5. Base URL có query hoặc fragment: Bị từ chối PROOF_PUBLIC_BASE_URL_INVALID")
    void testQueryAndFragmentRejected(String urlWithQueryOrFragment) {
        AppException ex = assertThrows(AppException.class, () -> resolver.normalizePublicBaseUrl(urlWithQueryOrFragment));
        assertEquals(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("6. Base URL có user-info: Bị từ chối PROOF_PUBLIC_BASE_URL_INVALID")
    void testUserInfoRejected() {
        String urlWithUserInfo = "https://user:password@api.homespace.vn";
        AppException ex = assertThrows(AppException.class, () -> resolver.normalizePublicBaseUrl(urlWithUserInfo));
        assertEquals(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_INVALID.getCode(), ex.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "172.31.34.19:8081",
            "172.16.0.1",
            "172.20.10.4:8080",
            "10.0.0.5:8081",
            "192.168.1.100:8080",
            "127.0.0.1:8081",
            "localhost:8081",
            "0.0.0.0:8081",
            "hs-api-service:8081",
            "hs-core-api",
            "gateway:8080",
            "backend.local",
            "server.internal",
            "app.docker"
    })
    @DisplayName("7. Không có config và request host là IP nội bộ/container: Ném lỗi PROOF_PUBLIC_BASE_URL_REQUIRED")
    void testPrivateOrInternalHostThrowsError(String privateHost) {
        AppException ex = assertThrows(AppException.class, () -> resolver.resolvePublicBaseUrl(
                null,
                "http",
                privateHost,
                privateHost,
                privateHost,
                8081
        ));
        assertEquals(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("8. Không có config nhưng có X-Forwarded-Host public hợp lệ: Fallback thành công")
    void testValidPublicForwardedHostFallback() {
        String resolved = resolver.resolvePublicBaseUrl(
                "",
                "https",
                "public-gateway.example.com",
                "internal-host:8081",
                "internal-host",
                8081
        );
        assertEquals("https://public-gateway.example.com", resolved);
    }

    @Test
    @DisplayName("9. Port chuẩn 80 và 443 không bị nối thừa vào URL")
    void testDefaultPortsOmitted() {
        assertEquals("https://api.homespace.vn", resolver.normalizePublicBaseUrl("https://api.homespace.vn:443"));
        assertEquals("http://api.homespace.vn", resolver.normalizePublicBaseUrl("http://api.homespace.vn:80"));
        assertEquals("https://api.homespace.vn:8443", resolver.normalizePublicBaseUrl("https://api.homespace.vn:8443"));
    }

    @Test
    @DisplayName("10. Private IP detection helper phân biệt chính xác")
    void testIsPrivateOrLocalHost() {
        assertTrue(resolver.isPrivateOrLocalHost("localhost"));
        assertTrue(resolver.isPrivateOrLocalHost("127.0.0.1:8081"));
        assertTrue(resolver.isPrivateOrLocalHost("172.31.34.19"));
        assertTrue(resolver.isPrivateOrLocalHost("172.16.1.1:8080"));
        assertTrue(resolver.isPrivateOrLocalHost("10.10.10.10"));
        assertTrue(resolver.isPrivateOrLocalHost("192.168.0.1:3000"));
        assertTrue(resolver.isPrivateOrLocalHost("hs-api-service"));
        assertTrue(resolver.isPrivateOrLocalHost("gateway.local"));
        assertTrue(resolver.isPrivateOrLocalHost(""));
        assertTrue(resolver.isPrivateOrLocalHost(null));

        assertFalse(resolver.isPrivateOrLocalHost("13zp4kj8-8080.asse.devtunnels.ms"));
        assertFalse(resolver.isPrivateOrLocalHost("api.homespace.vn"));
        assertFalse(resolver.isPrivateOrLocalHost("8.8.8.8"));
    }
}
