package com.hs.contract.service.converter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Slf4j
@Service
public class DocumentConversionService {

    @Value("${contract.gotenberg.url:http://localhost:3001}")
    private String gotenbergUrl;

    @Value("${contract.gotenberg.enabled:false}")
    private boolean gotenbergEnabled;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Chuyển đổi mảng byte của file DOCX sang PDF thông qua Gotenberg (LibreOffice container)
     * Trả về Optional<byte[]> nếu thành công, hoặc Optional.empty() nếu Gotenberg chưa bật hoặc lỗi
     */
    public Optional<byte[]> convertDocxToPdf(byte[] docxBytes, String fileName) {
        if (!gotenbergEnabled) {
            log.info("Gotenberg conversion is disabled (contract.gotenberg.enabled=false). Skipping PDF conversion.");
            return Optional.empty();
        }

        try {
            String convertEndpoint = gotenbergUrl.replaceAll("/+$", "") + "/forms/libreoffice/convert";
            log.info("Sending DOCX to Gotenberg for PDF conversion: {}", convertEndpoint);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            ByteArrayResource fileResource = new ByteArrayResource(docxBytes) {
                @Override
                public String getFilename() {
                    return fileName.endsWith(".docx") ? fileName : fileName + ".docx";
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("files", fileResource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<byte[]> response = restTemplate.postForEntity(convertEndpoint, requestEntity, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Successfully converted DOCX to PDF ({} bytes)", response.getBody().length);
                return Optional.of(response.getBody());
            } else {
                log.warn("Gotenberg returned non-2xx status: {}", response.getStatusCode());
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("Failed to convert DOCX to PDF via Gotenberg: {}. Preview will fallback to DOCX.", e.getMessage());
            return Optional.empty();
        }
    }
}
