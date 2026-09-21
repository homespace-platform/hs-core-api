package com.hs.payment.service;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PaymentProofUrlResolver {

    private static final Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /**
     * Chuẩn hóa và xác thực public base URL được cấu hình từ biến môi trường.
     * Quy tắc:
     * - Trim whitespace.
     * - Protocol bắt buộc là http hoặc https.
     * - Không chấp nhận javascript, file hoặc protocol khác.
     * - Không chấp nhận user-info.
     * - Không chấp nhận query string.
     * - Không chấp nhận fragment.
     * - Xóa toàn bộ dấu slash cuối.
     */
    public String normalizePublicBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new AppException(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_REQUIRED);
        }

        String trimmed = rawUrl.trim();

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            log.warn("Invalid URI syntax for publicBaseUrl: {}", e.getMessage());
            throw new AppException(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_INVALID);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            log.warn("Invalid scheme [{}] for publicBaseUrl. Only http/https allowed.", scheme);
            throw new AppException(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_INVALID);
        }

        if (uri.getUserInfo() != null) {
            log.warn("publicBaseUrl must not contain user-info");
            throw new AppException(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_INVALID);
        }

        if (uri.getRawQuery() != null) {
            log.warn("publicBaseUrl must not contain query parameters");
            throw new AppException(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_INVALID);
        }

        if (uri.getRawFragment() != null) {
            log.warn("publicBaseUrl must not contain fragments");
            throw new AppException(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_INVALID);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            log.warn("publicBaseUrl must contain a valid host");
            throw new AppException(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_INVALID);
        }

        // Reconstruct clean base URL without trailing slash
        StringBuilder sb = new StringBuilder();
        sb.append(scheme.toLowerCase()).append("://").append(host);

        int port = uri.getPort();
        if (port != -1) {
            boolean isDefaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                    || ("https".equalsIgnoreCase(scheme) && port == 443);
            if (!isDefaultPort) {
                sb.append(":").append(port);
            }
        }

        String path = uri.getPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            // Strip trailing slashes
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.endsWith("/")) {
                path = "";
            }
            sb.append(path);
        }

        return sb.toString();
    }

    /**
     * Xác định public base URL theo thứ tự ưu tiên:
     * 1. Biến môi trường PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL (nếu có giá trị)
     * 2. Nếu biến trống, chỉ fallback sang Forwarded / X-Forwarded-* khi host là public/reachable hợp lệ.
     * 3. Nếu chỉ tìm được IP nội bộ / private / loopback / container, ném lỗi PROOF_PUBLIC_BASE_URL_REQUIRED.
     */
    public String resolvePublicBaseUrl(
            String configuredPublicBaseUrl,
            String forwardedProto,
            String forwardedHost,
            String hostHeader,
            String serverName,
            int serverPort
    ) {
        // 1. Ưu tiên biến môi trường
        if (configuredPublicBaseUrl != null && !configuredPublicBaseUrl.isBlank()) {
            return normalizePublicBaseUrl(configuredPublicBaseUrl);
        }

        // 2. Kiểm tra fallback từ request headers
        String candidateHost = forwardedHost;
        if (candidateHost == null || candidateHost.isBlank()) {
            candidateHost = hostHeader;
        }

        String candidateProto = forwardedProto;
        if (candidateProto == null || candidateProto.isBlank()) {
            candidateProto = "http";
        }

        if (candidateHost != null && !candidateHost.isBlank()) {
            // Loại bỏ host-header injection hoặc ký tự lạ
            if (candidateHost.matches("^[a-zA-Z0-9.:\\-_]+$") && !isPrivateOrLocalHost(candidateHost)) {
                return normalizePublicBaseUrl(candidateProto + "://" + candidateHost);
            }
        }

        // 3. Fallback serverName/port nếu có
        if (serverName != null && !serverName.isBlank() && !isPrivateOrLocalHost(serverName)) {
            String url = candidateProto + "://" + serverName + (serverPort != 80 && serverPort != 443 && serverPort > 0 ? ":" + serverPort : "");
            return normalizePublicBaseUrl(url);
        }

        // 4. Chỉ tìm được IP nội bộ, loopback hoặc container -> Không âm thầm sinh QR sai
        log.warn("Cannot determine public base URL. Configured: [{}], Candidate host: [{}]. Raising PROOF_PUBLIC_BASE_URL_REQUIRED.",
                configuredPublicBaseUrl, candidateHost);
        throw new AppException(PaymentErrorCode.PROOF_PUBLIC_BASE_URL_REQUIRED);
    }

    /**
     * Kiểm tra xem host có phải là IP riêng (Private IP RFC 1918), Loopback, Link-Local hoặc tên container nội bộ hay không.
     */
    public boolean isPrivateOrLocalHost(String hostWithPort) {
        if (hostWithPort == null || hostWithPort.isBlank()) {
            return true;
        }

        String host = hostWithPort.trim().toLowerCase();
        // Bỏ port nếu có
        if (host.contains(":")) {
            int colonIdx = host.indexOf(":");
            host = host.substring(0, colonIdx);
        }

        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "0.0.0.0".equals(host) || "::1".equals(host)) {
            return true;
        }

        // Tên service nội bộ không có dấu chấm (e.g. hs-api-service, hs-core-api, gateway)
        if (!host.contains(".")) {
            return true;
        }

        if (host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".docker")) {
            return true;
        }

        // Kiểm tra IPv4
        Matcher matcher = IPV4_PATTERN.matcher(host);
        if (matcher.matches()) {
            try {
                int o1 = Integer.parseInt(matcher.group(1));
                int o2 = Integer.parseInt(matcher.group(2));
                int o3 = Integer.parseInt(matcher.group(3));
                int o4 = Integer.parseInt(matcher.group(4));

                if (o1 < 0 || o1 > 255 || o2 < 0 || o2 > 255 || o3 < 0 || o3 > 255 || o4 < 0 || o4 > 255) {
                    return true;
                }

                // 127.0.0.0/8 (Loopback)
                if (o1 == 127) return true;
                // 10.0.0.0/8 (Private RFC 1918)
                if (o1 == 10) return true;
                // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255) (Private RFC 1918)
                if (o1 == 172 && (o2 >= 16 && o2 <= 31)) return true;
                // 192.168.0.0/16 (Private RFC 1918)
                if (o1 == 192 && o2 == 168) return true;
                // 169.254.0.0/16 (Link-local)
                if (o1 == 169 && o2 == 254) return true;
                // 0.0.0.0
                if (o1 == 0 && o2 == 0 && o3 == 0 && o4 == 0) return true;

            } catch (NumberFormatException ignored) {
                return true;
            }
        }

        return false;
    }
}
