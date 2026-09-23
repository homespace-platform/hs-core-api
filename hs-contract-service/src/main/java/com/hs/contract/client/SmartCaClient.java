package com.hs.contract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.config.SmartCaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Client gọi VNPT SmartCA API.
 *
 * <p>Tất cả field nhạy cảm (sp_password, signature_value, cert_data)
 * đều KHÔNG được log.</p>
 */
@Slf4j
@Component
public class SmartCaClient {

    private final SmartCaProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    public SmartCaClient(SmartCaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (properties.getConnectTimeout() != null) {
            factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        }
        if (properties.getReadTimeout() != null) {
            factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        }
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Tra chứng thư SmartCA của một CCCD.
     *
     * @param cccd CCCD của người ký
     * @param optionalSerial nếu đã biết serial, điền vào để lọc
     * @return danh sách chứng thư hợp lệ (có thể rỗng)
     */
    public List<SmartCaCertificateDto> getCertificates(String cccd, String optionalSerial) {
        properties.requireConfigured();
        String url = url("/v1/credentials/get_certificate");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sp_id", properties.getSpId());
        body.put("sp_password", properties.getSpPassword());
        body.put("user_id", cccd);
        body.put("serial_number", optionalSerial != null ? optionalSerial.trim() : "");
        body.put("transaction_id", UUID.randomUUID().toString());

        log.info("SmartCA getCertificates for CCCD: {}***", maskCccd(cccd));
        try {
            String raw = post(url, body);
            return parseCertificates(raw, cccd);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("SmartCA getCertificates unexpected error: {}", e.getMessage());
            throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
        }
    }

    /**
     * Gửi yêu cầu ký số lên VNPT.
     *
     * @return {@link SmartCaSignResult} chứa providerTranCode để poll sau này
     */
    public SmartCaSignResult createSignatureRequest(SmartCaSignParams params) {
        properties.requireConfigured();
        String url = url("/v1/signatures/sign");

        String transactionDesc = (params.docName() != null && !params.docName().isBlank())
                ? params.docName().trim()
                : "Ký hợp đồng " + params.docId();

        Map<String, Object> signFile = new LinkedHashMap<>();
        signFile.put("file_type", "pdf");
        signFile.put("data_to_be_signed", params.hashHex());
        signFile.put("doc_id", params.docId());
        signFile.put("sign_type", "hash");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sp_id", properties.getSpId());
        body.put("sp_password", properties.getSpPassword());
        body.put("user_id", params.userCccd());
        body.put("transaction_id", params.transactionId());
        body.put("transaction_desc", transactionDesc);
        body.put("serial_number", params.certificateSerial() != null ? params.certificateSerial().trim() : "");
        body.put("time_stamp", TS_FMT.format(Instant.now()));
        body.put("sign_files", List.of(signFile));

        // Polling is the authoritative completion path. Do not expose an unauthenticated
        // callback_url until VNPT callback authentication is agreed and implemented.

        log.info("SmartCA createSignatureRequest docId={}, transactionId={}", params.docId(), params.transactionId());
        try {
            String raw = post(url, body);
            return parseSignResult(raw, params.transactionId());
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("SmartCA createSignatureRequest unexpected error: {}", e.getMessage());
            throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
        }
    }

    /**
     * Poll trạng thái một giao dịch ký.
     *
     * @param transactionId transaction_id gửi trong yêu cầu ký (đúng theo VNPT API 4.3)
     * @param expectedDocId docId để match đúng chữ ký trong mảng signatures
     */
    public SmartCaStatusResult getSignatureStatus(String transactionId, String expectedDocId) {
        properties.requireConfigured();
        String url = url("/v1/signatures/sign/" + transactionId + "/status");

        log.debug("SmartCA getSignatureStatus transactionId={}", transactionId);
        try {
            String raw = post(url, "");
            return parseStatusResult(raw, transactionId, expectedDocId);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("SmartCA getSignatureStatus unexpected error: {}", e.getMessage());
            throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
        }
    }

    // =========================================================================
    // DTOs (inner records — minimal, only what we need)
    // =========================================================================

    public record SmartCaCertificateDto(
            String serialNumber,
            String subject,
            String issuer,
            String certStatus,
            Instant validFrom,
            Instant validTo,
            String certData,
            String chainData
    ) {
        public boolean isActiveAndValid() {
            Instant now = Instant.now();
            return ("ACTIVE".equalsIgnoreCase(certStatus) || "VALID".equalsIgnoreCase(certStatus))
                    && (validFrom == null || !now.isBefore(validFrom))
                    && (validTo == null || now.isBefore(validTo));
        }
    }

    public record SmartCaSignParams(
            String userCccd,
            String certificateSerial,
            String transactionId,
            String docId,
            String docName,
            String hashHex
    ) {}

    public record SmartCaSignResult(
            String providerTransactionId,
            String providerTranCode,
            String status,
            String message
    ) {}

    public record SmartCaStatusResult(
            String providerTransactionId,
            String providerTranCode,
            String status,
            String signatureValue,
            String certData,
            String chainData,
            Instant signedTime,
            String failureCode,
            String failureMessage
    ) {
        public boolean isSigned() {
            return "SIGNED".equalsIgnoreCase(status)
                    && signatureValue != null && !signatureValue.isBlank();
        }

        public boolean isRejectedOrFailed() {
            return "REJECTED".equalsIgnoreCase(status)
                    || "EXPIRED".equalsIgnoreCase(status)
                    || "FAILED".equalsIgnoreCase(status);
        }
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    List<SmartCaCertificateDto> parseCertificates(String raw, String expectedCccd) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            JsonNode root = objectMapper.readTree(raw);
            int code = root.path("status_code").asInt(root.path("status").asInt(-1));
            String message = root.path("message").asText("");
            if (code != 0 && code != 200) {
                log.warn("SmartCA getCertificates error code={} msg={}", code, message);
                if (code == 401) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_AUTH_FAILED);
                if (code == 403) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_ACCESS_DENIED);
                if (code == 404) return Collections.emptyList();
                if (code == 400) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_INVALID_REQUEST);
                throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
            }

            JsonNode data = root.path("data");
            List<JsonNode> certNodes = new ArrayList<>();
            if (data.has("user_certificates") && data.get("user_certificates").isArray()) {
                data.get("user_certificates").forEach(certNodes::add);
            } else if (data.isArray()) {
                data.forEach(certNodes::add);
            } else if (data.isObject() && !data.isEmpty()) {
                certNodes.add(data);
            }

            List<SmartCaCertificateDto> result = new ArrayList<>();
            for (JsonNode item : certNodes) {
                String serial = item.path("serial_number").asText(item.path("serial").asText("")).trim();
                if (serial.isBlank()) continue;
                String certData = item.path("cert_data").asText("");
                X509Certificate x509 = null;
                if (!certData.isBlank()) {
                    try {
                        // VNPT inserts CRLF into Base64 cert_data. Strip whitespace only;
                        // keep the strict decoder so other malformed characters still fail.
                        x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                                .generateCertificate(new ByteArrayInputStream(
                                        Base64.getDecoder().decode(certData.replaceAll("\\s+", ""))));
                    } catch (Exception ex) {
                        log.warn("SmartCA returned an invalid X.509 certificate for serial={} reason={}",
                                maskSerial(serial), ex.getClass().getSimpleName());
                        continue;
                    }
                }
                String subject = item.path("cert_subject").asText(item.path("subject").asText("")).trim();
                if (subject.isBlank() && x509 != null) subject = x509.getSubjectX500Principal().getName();
                if (!subjectMatchesCccd(subject, expectedCccd)) {
                    log.warn("SmartCA cert serial={} subject does not match CCCD {}***", maskSerial(serial), maskCccd(expectedCccd));
                    continue;
                }
                // VNPT returns a machine-readable code and a localized display label.
                // Only the code (VALID/ACTIVE) should decide whether signing is allowed.
                String certStatus = item.path("cert_status_code").asText("").trim();
                if (certStatus.isBlank()) {
                    certStatus = item.path("cert_status").asText(item.path("status").asText("")).trim();
                }
                Instant validFrom = x509 != null ? x509.getNotBefore().toInstant()
                        : parseInstant(item.path("cert_valid_from").asText(item.path("valid_from").asText(null)));
                Instant validTo = x509 != null ? x509.getNotAfter().toInstant()
                        : parseInstant(item.path("cert_valid_to").asText(item.path("valid_to").asText(null)));
                JsonNode chainNode = item.path("chain_data");
                String chainData = chainNode.isMissingNode() || chainNode.isNull() ? ""
                        : (chainNode.isTextual() ? chainNode.asText() : chainNode.toString());

                SmartCaCertificateDto dto = new SmartCaCertificateDto(serial, subject,
                        x509 != null ? x509.getIssuerX500Principal().getName() : item.path("issuer").asText("").trim(),
                        certStatus, validFrom, validTo, certData, chainData);
                if (dto.isActiveAndValid()) result.add(dto);
            }
            return result;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse SmartCA certificates response: {}", e.getMessage(), e);
            throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
        }
    }

    private SmartCaSignResult parseSignResult(String raw, String clientTxId) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            int code = root.path("status_code").asInt(root.path("status").asInt(-1));
            String message = root.path("message").asText("");
            if (code != 0 && code != 200) {
                log.warn("SmartCA sign rejected txId={}: code={} msg={}", clientTxId, code, message);
                if (code == 401) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_AUTH_FAILED);
                if (code == 403) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_ACCESS_DENIED);
                if (code == 400) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_INVALID_REQUEST);
                throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
            }
            JsonNode data = root.path("data");
            // VNPT trả về transaction_id (HomeSpace's ID) và tran_code (VNPT's reference)
            String providerTxId = data.path("transaction_id").asText(clientTxId).trim();
            // tran_code là ID mà VNPT dùng để poll status — có thể là tran_id hoặc tran_code
            String tranCode = data.path("tran_code").asText(data.path("tran_id").asText("")).trim();
            if (tranCode.isBlank()) tranCode = providerTxId; // fallback
            return new SmartCaSignResult(providerTxId, tranCode, data.path("status").asText("PENDING"), message);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse SmartCA sign response: {}", e.getMessage(), e);
            throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
        }
    }

    private SmartCaStatusResult parseStatusResult(String raw, String queryId, String expectedDocId) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            int code = root.path("status_code").asInt(root.path("status").asInt(-1));
            if (code != 0 && code != 200 && (root.has("status_code") || root.has("status"))) {
                String msg = root.path("message").asText("");
                log.warn("SmartCA status check returned code={}: {}", code, msg);
                if (code == 404) throw new AppException(ContractErrorCode.SIGNATURE_REQUEST_NOT_FOUND);
                if (code == 401) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_AUTH_FAILED);
                if (code == 403) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_ACCESS_DENIED);
                throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
            }

            JsonNode data = root.path("data");
            String status = data.path("status").asText(root.path("status_name").asText("PENDING")).toUpperCase();

            // Tìm signature_value trong mảng signatures theo docId
            String signatureValue = null;
            Instant signedTime = null;
            String certData = null;
            String chainData = null;

            if (data.has("signatures") && data.get("signatures").isArray()) {
                JsonNode arr = data.get("signatures");
                JsonNode match = null;
                for (JsonNode sig : arr) {
                    String docId = sig.path("doc_id").asText("").trim();
                    if (expectedDocId != null && expectedDocId.equalsIgnoreCase(docId)) {
                        match = sig;
                        break;
                    } else if (expectedDocId == null) {
                        match = sig;
                        break;
                    }
                }
                if (match != null) {
                    signatureValue = match.path("signature_value").asText(null);
                    String ts = match.path("timestamp_signature").asText(null);
                    if (ts != null) signedTime = parseInstant(ts);
                }
            }
            // Fallback to data level
            if (signatureValue == null) signatureValue = data.path("signature_value").asText(null);
            if (signedTime == null) signedTime = parseInstant(data.path("signed_time").asText(null));

            certData = data.path("cert_data").asText(null);
            JsonNode chainNode = data.path("chain_data");
            if (!chainNode.isMissingNode() && !chainNode.isNull()) {
                chainData = chainNode.isTextual() ? chainNode.asText() : chainNode.toString();
            }

            // Nếu có signature_value thì coi là SIGNED (trừ khi bị từ chối)
            if (signatureValue != null && !signatureValue.isBlank()
                    && !"REJECTED".equalsIgnoreCase(status)
                    && !"EXPIRED".equalsIgnoreCase(status)
                    && !"FAILED".equalsIgnoreCase(status)) {
                status = "SIGNED";
            }

            String tranCode = data.path("tran_code").asText(queryId);
            String providerTxId = data.path("transaction_id").asText(queryId);
            String failureCode = data.path("error_code").asText(null);
            String failureMsg = data.path("error_message").asText(root.path("message").asText(null));

            return new SmartCaStatusResult(providerTxId, tranCode, status, signatureValue,
                    certData, chainData, signedTime, failureCode, failureMsg);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse SmartCA status response: {}", e.getMessage(), e);
            throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String post(String url, Object body) {
        try {
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("SmartCA HTTP {} for url={}", status, url);
            if (status == 400) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_INVALID_REQUEST);
            if (status == 401) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_AUTH_FAILED);
            if (status == 403) throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_ACCESS_DENIED);
            if (status == 404) throw new AppException(ContractErrorCode.SIGNATURE_REQUEST_NOT_FOUND);
            throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
        } catch (ResourceAccessException e) {
            log.error("SmartCA network/timeout error: {}", e.getMessage());
            throw new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE);
        }
    }

    private String url(String path) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private boolean subjectMatchesCccd(String subject, String cccd) {
        if (subject == null || cccd == null) return false;
        String norm = subject.toUpperCase().replaceAll("\\s+", "");
        String t = cccd.trim();
        return norm.contains("UID:" + t) || norm.contains("UID=" + t)
                || norm.contains("CCCD:" + t) || norm.contains("CCCD=" + t)
                || norm.contains("CMND:" + t) || norm.contains("CMND=" + t)
                || norm.contains(t);
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (DateTimeParseException ignored) {}
        try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                    .parse(s, Instant::from);
        } catch (Exception ignored) {}
        try {
            return DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                    .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                    .parse(s, Instant::from);
        } catch (Exception ignored) {}
        return null;
    }

    public static String maskCccd(String cccd) {
        if (cccd == null || cccd.length() <= 4) return "***";
        return cccd.substring(0, 3) + "***" + cccd.substring(cccd.length() - 2);
    }

    public static String maskSerial(String serial) {
        if (serial == null || serial.length() <= 6) return "***";
        return serial.substring(0, 4) + "..." + serial.substring(serial.length() - 4);
    }
}
