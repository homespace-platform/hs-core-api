package com.hs.contract.service.engine;

import com.hs.contract.dto.catalog.TemplateFieldDefinition;
import com.hs.contract.dto.response.TemplateValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateAnalysisService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private final ContractFieldCatalog catalog;

    public TemplateValidationResult analyzeTemplate(InputStream inputStream) {
        Set<String> detected = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            // 1. Quét đoạn văn bản trong body
            for (XWPFParagraph p : document.getParagraphs()) {
                extractPlaceholders(p.getText(), detected);
            }

            // 2. Quét bảng biểu
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph p : cell.getParagraphs()) {
                            extractPlaceholders(p.getText(), detected);
                        }
                    }
                }
            }

            // 3. Quét Header
            for (XWPFHeader header : document.getHeaderList()) {
                for (XWPFParagraph p : header.getParagraphs()) {
                    extractPlaceholders(p.getText(), detected);
                }
            }

            // 4. Quét Footer
            for (XWPFFooter footer : document.getFooterList()) {
                for (XWPFParagraph p : footer.getParagraphs()) {
                    extractPlaceholders(p.getText(), detected);
                }
            }
        } catch (Exception e) {
            log.error("Lỗi khi đọc file Word mẫu DOCX: {}", e.getMessage(), e);
            return TemplateValidationResult.builder()
                    .valid(false)
                    .warnings(List.of("Không thể đọc định dạng file DOCX: " + e.getMessage()))
                    .build();
        }

        List<String> validList = new ArrayList<>();
        List<String> invalidList = new ArrayList<>();

        for (String ph : detected) {
            if (catalog.isValidPlaceholder(ph)) {
                validList.add(ph);
            } else {
                invalidList.add(ph);
            }
        }

        // Kiểm tra các trường bắt buộc
        List<String> missingRequired = new ArrayList<>();
        for (TemplateFieldDefinition def : catalog.getAllDefinitions()) {
            if (def.isRequired() && !detected.contains(def.getKey())) {
                missingRequired.add(def.getKey() + " (" + def.getLabel() + ")");
            }
        }

        if (!invalidList.isEmpty()) {
            warnings.add("Phát hiện " + invalidList.size() + " mã trường không hợp lệ hoặc sai chính tả.");
        }
        if (!missingRequired.isEmpty()) {
            warnings.add("Mẫu Word thiếu " + missingRequired.size() + " trường bắt buộc quan trọng.");
        }

        boolean isValid = invalidList.isEmpty();

        return TemplateValidationResult.builder()
                .valid(isValid)
                .detectedPlaceholders(new ArrayList<>(detected))
                .validPlaceholders(validList)
                .invalidPlaceholders(invalidList)
                .missingRequiredPlaceholders(missingRequired)
                .warnings(warnings)
                .build();
    }

    private void extractPlaceholders(String text, Set<String> detected) {
        if (text == null || !text.contains("{{")) {
            return;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String rawTag = matcher.group(1).trim();
            // Bỏ các tiền tố phụ nếu có nhưng giữ lại '#' của poi-tl table
            if (rawTag.startsWith("/") || rawTag.startsWith("?")) {
                continue;
            }
            detected.add(rawTag);
        }
    }
}
